package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;

public record TransferOutcome(Transfer transfer, Kind kind) {
    public enum Kind {
        /** Brand-new transfer, this call performed the debit/credit. */
        CREATED,
        /** Brand-new transfer, this call attempted the debit and it failed (insufficient funds). */
        DECLINED,
        /** Same idempotency key + same body as an earlier request — no money moved this call. */
        REPLAYED
    }
}
