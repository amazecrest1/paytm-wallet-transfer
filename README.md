# Wallet & P2P Transfer

A small wallet service with peer-to-peer transfers, built for the Paytm R2 "Deploy & Reason" exercise.
The graded properties are conservation, no-overdraft, exactly-once transfers, and race-free
get-or-create — all under real concurrency. See [`DESIGN.md`](DESIGN.md) for the full design writeup
and rationale.

## Stack

Java 21, Spring Boot 3, PostgreSQL 16, Flyway, Maven, Docker.

## Run it locally

```bash
docker compose up --build
```

This brings up the app (`:8080`) and Postgres in one command. Health check:
`curl http://localhost:8080/actuator/health`.

Default dev bearer tokens (see `AUTH_TOKENS` in `docker-compose.yml`):

| Token | User |
|---|---|
| `dev-token-alice` | alice |
| `dev-token-bob` | bob |
| `dev-token-carol` | carol |
| `dev-token-dave` | dave |
| `dev-token-erin` | erin |

## API

All endpoints require `Authorization: Bearer <token>`. Money is always integer paise.

- `POST /wallets` — get-or-create a wallet for the caller.
- `GET /wallets/{id}` — current balance.
- `POST /wallets/{id}/deposit` — `{ "amount_paise", "idempotency_key" }`. **Beyond the exercise's
  minimum API** — added because transfers alone can never bootstrap the system's first balance.
  Injects money from outside the closed P2P loop (think: a linked bank account), so it's deliberately
  exempt from the conservation invariant, which is scoped to transfers only (see DESIGN.md). `403` if
  the caller doesn't own the wallet.
- `POST /transfers` — `{ "from", "to", "amount_paise", "idempotency_key" }`. `403` if the caller
  doesn't own `from` (see DESIGN.md — a deliberate decision, not something the brief requires).
- `GET /transfers/{id}` — transfer status.

```bash
curl -X POST localhost:8080/wallets -H "Authorization: Bearer dev-token-alice"
curl -X POST localhost:8080/transfers -H "Authorization: Bearer dev-token-alice" \
  -H "Content-Type: application/json" \
  -d '{"from":"<wallet-a>","to":"<wallet-b>","amount_paise":500,"idempotency_key":"abc123"}'
```

## Tests

```bash
mvn test
```

Runs unit tests plus Testcontainers-backed integration and concurrency tests against a real
Postgres (Docker required). The concurrency tests (`src/test/.../concurrency/`) fire genuinely
concurrent HTTP bursts — 50-way wallet creation, 30-way idempotent retries, 90-way crossing
transfers, same-pair overdraft contention — and assert on final DB-observable state, not on mirrored
implementation logic.

> **Note on the dev sandbox this was built in:** if you're running Docker via **colima** on a very
> recent Docker Engine (29.x), Testcontainers' bundled client may fail version negotiation
> (`client version 1.32 is too old`). Fix with a `~/.testcontainers.properties` containing
> `docker.host=unix://<path-to-colima-socket>` and `api.version=1.51`, plus
> `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (colima's bind-mount path
> inside its own VM) before running `mvn test`. Not needed on Docker Desktop or a native Linux host.

## Burst test

```bash
./burst.sh                                    # local docker-compose instance
BASE_URL=https://your-app ./burst.sh          # deployed instance
```

Fully API-driven — no database access needed. Reproduces every gate from the brief against a
*running* instance, funding wallets through the real deposit endpoint rather than reaching around the
API: 50 concurrent wallet creations, a 30-way idempotent deposit storm, a 30-way idempotent transfer
retry storm, same-key/different-body conflicts (both endpoints), a 90-way crossing-transfer
conservation check, and single-pair overdraft contention. Requires only `curl`, `jq`, `xargs`.

## Deploy

Free-tier target: a Docker-based host (Render/Railway/Fly.io/Koyeb) with a free managed Postgres.
Set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `AUTH_TOKENS`, `PORT` as environment
variables (see `.env.example`). Flyway migrates the schema automatically on startup.

## Observability

- **Logs:** structured JSON (`logback-spring.xml` + logstash encoder) on stdout, with a
  `correlation_id` (from `X-Request-Id`, echoed back) and `user_id` in every line via MDC, plus
  domain events (`transfer_created`, `transfer_debited`, `transfer_credited`,
  `transfer_declined_insufficient_funds`, `transfer_idempotent_replay`, `wallet_created`,
  `deposit_completed`, `deposit_idempotent_replay`, ...) as structured fields.
- **Metrics:** `/actuator/prometheus` — request rate/latency/error rate come from Spring Boot's
  built-in HTTP metrics; domain counters (`wallet_creations_total`, `transfers_completed_total`,
  `transfers_declined_total{reason="insufficient_funds"}`, `transfers_idempotent_replays_total`,
  `transfers_idempotency_key_conflicts_total`, `deposits_completed_total`,
  `deposits_idempotent_replays_total`, `deposits_idempotency_key_conflicts_total`) are custom (see
  `DomainMetrics`).
- **Health:** `/actuator/health`, used by the Docker `HEALTHCHECK`.
