package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Unlike a Transfer, a Deposit has no failure mode besides validation/idempotency-conflict (there is
 * no balance to check against) — so unlike {@link Transfer} there is no status field: every row in
 * this table represents a deposit that already succeeded.
 */
public record Deposit(
        UUID id,
        String userId,
        UUID walletId,
        long amountPaise,
        String idempotencyKey,
        String requestHash,
        Instant createdAt
) {
}
