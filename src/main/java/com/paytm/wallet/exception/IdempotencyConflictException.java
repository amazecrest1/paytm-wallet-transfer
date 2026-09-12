package com.paytm.wallet.exception;

/**
 * Thrown when the same (initiator, idempotency_key) is reused with a request body
 * whose hash does not match the originally stored request. Maps to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key reused with a different request body: " + idempotencyKey);
    }
}
