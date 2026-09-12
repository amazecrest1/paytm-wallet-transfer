# Design write-up — Wallet & P2P Transfer

## Data model

Two tables. `wallets(id, user_id UNIQUE, balance_paise BIGINT CHECK >= 0, timestamps)`.
`transfers(id, initiator_user_id, from_wallet_id FK, to_wallet_id FK, amount_paise BIGINT CHECK > 0,
idempotency_key, request_hash, status, decline_reason, timestamps, UNIQUE(initiator_user_id,
idempotency_key), CHECK(from_wallet_id <> to_wallet_id))`. No separate ledger/double-entry table —
`transfers` is the audit trail; a full ledger was considered and deliberately deferred as scope the
exercise doesn't grade. Money is `BIGINT` paise everywhere: storage, Java (`long`), and the JSON wire
format (`amount_paise` is always an integer paise value, never a decimal rupee amount).

## The simplest-correct mechanism

Two independent decisions, deliberately not conflated:

**Lock order** (deadlock safety): before either wallet's balance is touched, both are locked with
`SELECT ... WHERE id = :id FOR UPDATE`, issued as two separate single-row statements, always in
ascending-wallet-id order. Every transfer in the system acquires locks through this same path in the
same order, so two transfers touching the same pair in opposite roles (A→B racing B→A) never form a
wait cycle between each other.

**Movement order** (conservation/no-overdraft correctness): once — and only once — both locks are
held, the source is always debited first via an atomic conditional `UPDATE wallets SET balance_paise
= balance_paise - :amt WHERE id = :id AND balance_paise >= :amt`, checking rows-affected; the
destination is credited second, unconditionally, only if the debit affected a row. This order is
fixed regardless of which wallet's id happens to be lower — it is not tied to the lock order at all.
Decoupling the two is what makes this safe: locking both rows first means the later debit/credit
statements acquire no new locks (this transaction already holds both), so ordering them by business
role instead of by id cannot introduce a deadlock, while still guaranteeing the credit is structurally
unreachable unless the debit already succeeded — regardless of which wallet sorts lower.

An earlier draft of this document described the movement order as tied to the lock order
(debit-or-credit-whichever-comes-first-in-id-order): if the destination happened to sort first, it
would be credited before the source's balance was ever checked, and a subsequent debit failure would
leave money created from nothing. That description was never actually correct and was caught in
review before being carried into a design any further — the point above is the corrected statement.

**Rejected alternatives:** `SERIALIZABLE` isolation is correct but adds a mandatory
retry-on-serialization-failure loop and a materially higher abort rate under contention on a small,
hot wallet set — solving a problem the ordered-lock approach doesn't have. An *unordered*
`SELECT ... FOR UPDATE` (or an unordered conditional `UPDATE` pair) deadlocks under exactly the
A→B/B→A crossing the brief calls out. A read-balance-into-app-then-write-back approach is rejected
outright — it's a lost-update bug waiting to happen, not a design trade-off.

## An honest correction, found by adversarial testing

The first version of the locking step used one two-row statement
(`WHERE id IN (:a,:b) ORDER BY id FOR UPDATE`) reasoned to be safe because `EXPLAIN` confirms Postgres
sorts before locking (`LockRows → Sort → Scan`). Under a live 24-way concurrent-burst test against
the **same** wallet pair, it still deadlocked — badly (near-total livelock, one success in 90+
seconds). The actual cause wasn't the two-row statement at all: `transfers.from_wallet_id` /
`to_wallet_id` are foreign keys into `wallets(id)`, and Postgres enforces that by taking an *implicit*
`FOR KEY SHARE` lock on the referenced wallet rows the instant `INSERT INTO transfers` runs — in
request order (`from` then `to`), not our sorted order, and *before* our own explicit lock ever ran
(the insert happened first in the original code). Under many concurrent inserts on the same pair,
every transaction ends up holding a `FOR KEY SHARE` the others need to upgrade past, and none can
release it until *they* get their own `FOR UPDATE` — a genuine N-way deadlock, arising from the
interaction between an automatic FK lock and an explicit one, not from the explicit lock's own
ordering. **Fix:** acquire the explicit ordered `FOR UPDATE` locks *before* inserting the transfers
row, so the FK's own check lands on a lock this transaction already holds — a no-op. After the fix,
the identical 24-way burst went from ~100s with a near-total livelock to 4.2s with zero retries.

**On the retry loop, for precision:** `TransferService` also wraps the transaction in a bounded,
jittered retry on `PessimisticLockingFailureException` (10 attempts, exponential backoff capped at
400ms). This is optional defensive resilience, not part of how correctness is achieved, and removing
it would not make the system incorrect — only, in one rare situation, noisier. Correctness
(conservation, no-overdraft, exactly-once) comes entirely from the deterministic lock order, the fixed
debit-then-credit movement order, and transactional atomicity — none of that depends on retrying
anything. What the retry actually absorbs is a separate, narrower case: under very high fan-out on the
exact same wallet pair, Postgres's deadlock detector can still occasionally abort a transaction as an
artifact of how it tracks wait-for edges among many simultaneous waiters on one resource — documented,
expected behavior under heavy single-row contention. Left unhandled, that would surface as an honest,
correct 5xx, not corrupt anything. The retry exists purely so the caller sees a clean result instead
of an occasional transient error, and is only safe to add because a transaction that loses this kind
of abort is guaranteed to be rolled back in full by Postgres (idempotency-key insert included) — a
retry is indistinguishable from the client resending the same request.

## Where idempotency lives

`UNIQUE(initiator_user_id, idempotency_key)` on `transfers`, and the row is **inserted as the first
statement of the same transaction** that later performs the debit/credit — not checked in a separate
query beforehand. A concurrent duplicate either blocks on the unique index until the first
transaction resolves (then reads its committed result) or loses the insert race outright; there is no
gap in which two callers can both believe they're first (the TOCTOU a separate check-then-insert
would have). Same key + different body: the stored `request_hash` (SHA-256 of `from|to|amount_paise`)
is compared, and a mismatch is a `409`.

One precise point on sequencing: the wallet locks are acquired *before* this insert (to close the
FK-lock hazard above), so a losing/conflicting request does still lock and release both wallets as a
side effect — "never touches a wallet" would overstate what happens. What's actually guaranteed is
narrower and is the property that matters: neither a conflicting nor a replayed request ever performs
a balance *mutation* — the losing path returns before the debit/credit statements are ever reached.

## Source-wallet authorization

Checked against the brief rather than assumed: the exercise says only "a simple bearer token per user
identifies the caller" and explicitly states auth sophistication isn't graded; neither the brief nor
the rubric's gates, reject triggers, or debrief questions mention wallet ownership at all. So this is
genuinely unspecified, not an implicit requirement — and a deliberate design decision, not a rubric
requirement: `POST /transfers` requires `from.user_id == initiator_user_id` (the identity resolved
from the bearer token), rejecting a mismatch with `403` before any locking or wallet mutation. Without
it, any authenticated user who learns another wallet's id could drain it while authenticated as
themselves — none of the four graded invariants would catch that, since balances still conserve
perfectly. The check is a single field comparison against data already being fetched, deliberately not
new auth infrastructure, so it doesn't reintroduce the "auth sophistication" the brief says to skip.
`to` is deliberately left unconstrained — receiving funds doesn't need the recipient's consent in any
real payment system either.

## Consistency vs. availability

Chose strong consistency — single-node Postgres, row-level locks, no async replication in the write
path — over availability. A request can block briefly behind a contended wallet's lock, and a DB
outage takes writes down entirely, rather than accepting an eventually-consistent or best-effort
balance. For money, a wallet that's briefly unavailable is a far better failure mode than one that's
fast but occasionally wrong.

## AI directed vs. decided

**Directed (I chose the approach; AI implemented it and I verified it):** the overall architecture
(single Spring Boot service, no queue/cache), the schema and every constraint on it, the choice of
atomic conditional `UPDATE` + ordered locking over `SERIALIZABLE`/unordered `FOR UPDATE`, the
idempotency-in-the-same-transaction design, the plain-JDBC-over-JPA call for the two SQL statements
where atomicity matters, the `422`-for-decline / status-derived-from-final-state API choice, and the
decision to defer a full ledger table and the reversal endpoint.

**AI-decided, then verified by running it:** the exact retry/backoff parameters (attempt count,
backoff curve) — tuned empirically against real contention rather than picked a priori. The FK/lock
ordering bug in the section above was *found* by AI-run adversarial concurrency tests, and the fix
was reasoned out and verified the same way, live against real Postgres, not asserted from theory.

**Caught by my own review, not the AI's:** the movement-order bug described above — the AI's first
design draft tied debit/credit order to id order, which I read closely enough to catch would violate
conservation exactly when the destination sorts lower and the debit fails. The AI had, separately,
already implemented the corrected fixed-order version for an unrelated reason (the FK-lock fix) and
simply never gone back to correct the written design to match — I asked for the design to be
re-derived properly rather than patched, and for source-wallet authorization to be checked against the
brief explicitly rather than assumed either way. Both are now first-class, named decisions rather than
things that happened to fall out of a different fix.

**What I'd defend without hesitation in an interview:** the entire locking/idempotency mechanism and
the FK-lock story above, since I traced it through actual Postgres behavior, not just accepted a
plausible-sounding explanation — and the movement-order/authorization corrections, since catching and
directing those was my own review, not something I took on faith.

## Free-tier cost

₹0 — free-tier compute (Render/Railway/Fly.io/Koyeb) + free-tier managed Postgres, no card on file.

## R3 readiness: `POST /transfers/{id}/reverse`

Not built — deliberately left as live-round material — but the architecture makes it a small
addition, not a redesign: it reuses `TransferService`'s exact retry-wrapped
lock→debit→credit primitive with `from`/`to` swapped, gets its own
`UNIQUE(initiator_user_id, idempotency_key)` row for its own exactly-once guarantee, and needs one
new guard — `UNIQUE(reversal_of_transfer_id)` — so a *different* idempotency key can't reverse the
same original transfer twice. The reversal's debit is still the same conditional `UPDATE ... WHERE
balance >= amount`, so a recipient who already spent the funds gets a clean decline for free, with no
special-casing.
