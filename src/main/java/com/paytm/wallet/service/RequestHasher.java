package com.paytm.wallet.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Hashes the business fields of a transfer request so a same-key replay can be compared against the
 * originally stored request without re-parsing/storing the raw JSON body. Deliberately excludes the
 * idempotency_key itself (it's the lookup key, not part of "is this the same request") and any
 * transport metadata (headers, correlation id) — only (from, to, amount_paise) determine equality.
 */
final class RequestHasher {

    private RequestHasher() {
    }

    static String hash(UUID from, UUID to, long amountPaise) {
        String canonical = from + "|" + to + "|" + amountPaise;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
