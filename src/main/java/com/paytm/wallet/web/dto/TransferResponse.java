package com.paytm.wallet.web.dto;

import com.paytm.wallet.domain.Transfer;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        String status,
        UUID from,
        UUID to,
        long amountPaise,
        String declineReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.id(),
                transfer.status().name(),
                transfer.fromWalletId(),
                transfer.toWalletId(),
                transfer.amountPaise(),
                transfer.declineReason(),
                transfer.createdAt(),
                transfer.updatedAt()
        );
    }
}
