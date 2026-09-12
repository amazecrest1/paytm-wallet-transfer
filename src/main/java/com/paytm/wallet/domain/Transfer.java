package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        String initiatorUserId,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        String idempotencyKey,
        String requestHash,
        TransferStatus status,
        String declineReason,
        Instant createdAt,
        Instant updatedAt
) {
}
