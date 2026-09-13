-- Deposits inject money from OUTSIDE the closed P2P loop (e.g. a linked bank account) — this is
-- deliberately not part of "conservation" (that invariant is scoped to transfers only). Not in the
-- exercise's minimum API; added as a real endpoint so burst.sh can seed balances through the public
-- API instead of a direct SQL UPDATE.

CREATE TABLE deposits (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(128) NOT NULL,
    wallet_id       UUID NOT NULL REFERENCES wallets(id),
    amount_paise    BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_deposits_user_idem UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_deposits_amount_pos CHECK (amount_paise > 0)
);

CREATE INDEX idx_deposits_wallet ON deposits(wallet_id);
