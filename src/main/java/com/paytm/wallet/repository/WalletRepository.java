package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Wallet;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private static final RowMapper<Wallet> WALLET_MAPPER = (rs, rowNum) -> new Wallet(
            UUID.fromString(rs.getString("id")),
            rs.getString("user_id"),
            rs.getLong("balance_paise"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private static final RowMapper<WalletUpsertResult> UPSERT_MAPPER = (rs, rowNum) ->
            new WalletUpsertResult(WALLET_MAPPER.mapRow(rs, rowNum), rs.getBoolean("inserted"));

    /** created=true iff this call's INSERT is the one that won; false if it hit an existing row. */
    public record WalletUpsertResult(Wallet wallet, boolean created) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public WalletRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gate 1 mechanism: a single atomic upsert.
     * <p>
     * ON CONFLICT DO UPDATE (a harmless no-op self-assignment) instead of DO NOTHING purely so that
     * RETURNING always yields a row, whether this call won the insert race or lost it. This is a
     * single round trip and a single statement — the unique index on user_id is what actually makes
     * this race-free: two concurrent INSERTs for the same user_id cannot both "win"; Postgres's index
     * itself serializes them, so there is no window between "check" and "create" for a second caller
     * to slip through (unlike SELECT-then-INSERT in application code).
     */
    public WalletUpsertResult getOrCreate(String userId) {
        // xmax = 0 is a standard Postgres tell for "this row version was created by an INSERT in this
        // command, not touched by the ON CONFLICT DO UPDATE branch" — it's what lets us log/count
        // "wallet created" vs "get-or-create hit an existing wallet" accurately without a second query.
        String sql = """
                INSERT INTO wallets (user_id, balance_paise)
                VALUES (:userId, 0)
                ON CONFLICT (user_id) DO UPDATE SET user_id = wallets.user_id
                RETURNING id, user_id, balance_paise, created_at, updated_at, (xmax = 0) AS inserted
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        return jdbc.queryForObject(sql, params, UPSERT_MAPPER);
    }

    public Optional<Wallet> findById(UUID id) {
        String sql = """
                SELECT id, user_id, balance_paise, created_at, updated_at
                FROM wallets WHERE id = :id
                """;
        try {
            Wallet wallet = jdbc.queryForObject(sql, new MapSqlParameterSource("id", id), WALLET_MAPPER);
            return Optional.ofNullable(wallet);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Deadlock-avoidance step for a transfer touching two wallets.
     * <p>
     * Locks each wallet with its OWN single-row statement, issued in ascending-id order, rather than
     * one two-row {@code WHERE id IN (...) ORDER BY ... FOR UPDATE} statement. Both approaches sort
     * before locking on paper (confirmed via EXPLAIN: {@code LockRows -> Sort -> Scan}), but under
     * adversarial testing — many concurrent transfers hammering the exact same wallet pair — the
     * combined two-row statement produced far more Postgres-side deadlock_detected (40P01) errors
     * than this ever does. A single-row {@code WHERE id = :id FOR UPDATE} has no internal ordering for
     * Postgres to get right or wrong in the first place, which removes that failure mode entirely
     * rather than relying on a multi-row statement's locking order under contention.
     * <p>
     * Every transfer in the system takes locks through this same method in the same ascending order,
     * so two transfers touching the same pair in opposite directions (A->B and B->A) always request
     * locks in the same global order — whichever arrives first gets both, the other simply waits.
     */
    public List<Wallet> lockPairOrdered(UUID walletIdA, UUID walletIdB) {
        UUID first = walletIdA.compareTo(walletIdB) <= 0 ? walletIdA : walletIdB;
        UUID second = first.equals(walletIdA) ? walletIdB : walletIdA;
        Wallet firstWallet = lockSingle(first);
        Wallet secondWallet = lockSingle(second);
        return List.of(firstWallet, secondWallet);
    }

    /**
     * Locks one wallet row. {@link #lockPairOrdered} uses this twice, in order, for a transfer's two
     * wallets; a single-wallet operation like deposit uses it directly — the same FK-implied-lock
     * hazard applies even with one wallet (many concurrent deposits to the same wallet each take an
     * implicit FOR KEY SHARE via the deposits FK the instant their row is inserted; if that insert
     * happened before this lock, they'd all need to mutually upgrade past each other's shared lock,
     * the identical N-way deadlock pattern found for transfers) — so this must still run before the
     * deposit row is inserted, exactly as it does for transfers.
     */
    public Wallet lockSingle(UUID walletId) {
        String sql = """
                SELECT id, user_id, balance_paise, created_at, updated_at
                FROM wallets
                WHERE id = :id
                FOR UPDATE
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource("id", walletId), WALLET_MAPPER);
    }

    /**
     * Gate 3 mechanism (the debit half): an atomic conditional UPDATE.
     * <p>
     * The balance check and the write happen as one statement evaluated by Postgres against the
     * current row — there is no "read balance into app memory, compare, write back" gap for a
     * concurrent debit to race into. If two debits against the same wallet race, Postgres serializes
     * them at the row level: the second one's WHERE clause is re-evaluated against the first one's
     * committed result before it is allowed to affect any rows (EvalPlanQual), so a lost update is
     * not possible even at READ COMMITTED. Zero rows affected means the balance was insufficient at
     * the instant this statement executed — the caller must decline cleanly, never assume success.
     */
    public int conditionalDebit(UUID walletId, long amountPaise) {
        String sql = """
                UPDATE wallets
                   SET balance_paise = balance_paise - :amount, updated_at = now()
                 WHERE id = :walletId AND balance_paise >= :amount
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("walletId", walletId)
                .addValue("amount", amountPaise);
        return jdbc.update(sql, params);
    }

    /**
     * Credit is unconditional (crediting can never overdraw) but still takes the row's write lock,
     * which is why the pair must already be locked via {@link #lockPairOrdered} before this runs —
     * otherwise this statement's own lock acquisition could itself deadlock against a concurrent
     * transfer touching the same two wallets in the opposite role.
     */
    public int credit(UUID walletId, long amountPaise) {
        String sql = """
                UPDATE wallets
                   SET balance_paise = balance_paise + :amount, updated_at = now()
                 WHERE id = :walletId
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("walletId", walletId)
                .addValue("amount", amountPaise);
        return jdbc.update(sql, params);
    }
}
