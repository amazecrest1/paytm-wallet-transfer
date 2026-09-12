package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * balancePaise is always integer paise. Never a float/double/BigDecimal-as-rupees.
 */
public record Wallet(
        UUID id,
        String userId,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {
}
