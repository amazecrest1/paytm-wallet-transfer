package com.paytm.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record DepositRequest(
        @Positive long amountPaise,
        @NotBlank String idempotencyKey
) {
}
