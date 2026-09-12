package com.paytm.wallet.web;

import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InvalidRequestException;
import com.paytm.wallet.exception.NotWalletOwnerException;
import com.paytm.wallet.exception.TransferNotFoundException;
import com.paytm.wallet.exception.WalletNotFoundException;
import com.paytm.wallet.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Every failure mode maps to a specific 4xx with a clear message — the brief's "clean rejection"
 * requirement — never a raw 500/stack trace for an expected condition. Anything not caught here still
 * falls through to Spring Boot's default error handling, which itself never leaks a stack trace to
 * the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("wallet_not_found", e.getMessage()));
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotFound(TransferNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("transfer_not_found", e.getMessage()));
    }

    @ExceptionHandler(NotWalletOwnerException.class)
    public ResponseEntity<ErrorResponse> handleNotWalletOwner(NotWalletOwnerException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("forbidden", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("idempotency_key_conflict", e.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse("validation_failed", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("malformed_request_body", "Request body is missing or not valid JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unexpected_error", e);
        return ResponseEntity.internalServerError().body(new ErrorResponse("internal_error", "Something went wrong"));
    }
}
