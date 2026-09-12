package com.paytm.wallet.web.dto;

import com.paytm.wallet.domain.Wallet;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID walletId,
        String userId,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.userId(), wallet.balancePaise(), wallet.createdAt(), wallet.updatedAt());
    }
}
