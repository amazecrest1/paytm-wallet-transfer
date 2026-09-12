package com.paytm.wallet.exception;

import java.util.UUID;

/**
 * The brief and rubric are silent on source-wallet ownership — this check exists as a deliberate
 * design decision (see DESIGN.md), not a spec requirement: a transfer may only be initiated by the
 * owner of its {@code from} wallet. Maps to HTTP 403.
 */
public class NotWalletOwnerException extends RuntimeException {
    public NotWalletOwnerException(String initiatorUserId, UUID walletId) {
        super("User " + initiatorUserId + " does not own wallet " + walletId);
    }
}
