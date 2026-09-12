-- Wallet & P2P Transfer schema.
-- Money is always integer paise (BIGINT). Never NUMERIC/DOUBLE for money.

CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid()

CREATE TABLE wallets (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        VARCHAR(128) NOT NULL,
    balance_paise  BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),
    CONSTRAINT ck_wallets_balance_nonneg CHECK (balance_paise >= 0)
);

CREATE TABLE transfers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    initiator_user_id   VARCHAR(128) NOT NULL,
    from_wallet_id      UUID NOT NULL REFERENCES wallets(id),
    to_wallet_id        UUID NOT NULL REFERENCES wallets(id),
    amount_paise        BIGINT NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    request_hash        CHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    decline_reason      VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_transfers_initiator_idem UNIQUE (initiator_user_id, idempotency_key),
    CONSTRAINT ck_transfers_amount_pos CHECK (amount_paise > 0),
    CONSTRAINT ck_transfers_not_self CHECK (from_wallet_id <> to_wallet_id),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED'))
);

CREATE INDEX idx_transfers_from_wallet ON transfers(from_wallet_id);
CREATE INDEX idx_transfers_to_wallet   ON transfers(to_wallet_id);
