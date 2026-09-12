package com.paytm.wallet.exception;

/** Generic 400: malformed/semantically-invalid request that isn't caught by bean validation alone. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
