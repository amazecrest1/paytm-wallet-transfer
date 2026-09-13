package com.paytm.wallet.web.dto;

import com.paytm.wallet.domain.Deposit;

import java.time.Instant;
import java.util.UUID;

public record DepositResponse(
        UUID depositId,
        UUID walletId,
        long amountPaise,
        Instant createdAt
) {
    public static DepositResponse from(Deposit deposit) {
        return new DepositResponse(deposit.id(), deposit.walletId(), deposit.amountPaise(), deposit.createdAt());
    }
}
