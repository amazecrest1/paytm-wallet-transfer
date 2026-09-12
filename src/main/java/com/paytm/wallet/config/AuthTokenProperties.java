package com.paytm.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple bearer-token-per-user auth, exactly what the brief asks for and nothing more — no OAuth,
 * no JWT, no token issuance/expiry. Tokens are provided as a static "token:userId,token:userId" list
 * via the wallet.auth.tokens property (backed by the AUTH_TOKENS env var), parsed once at startup.
 */
@ConfigurationProperties(prefix = "wallet.auth")
public class AuthTokenProperties {

    private String tokens = "";

    public String getTokens() {
        return tokens;
    }

    public void setTokens(String tokens) {
        this.tokens = tokens;
    }

    public Map<String, String> asTokenToUserIdMap() {
        Map<String, String> map = new HashMap<>();
        if (tokens == null || tokens.isBlank()) {
            return map;
        }
        for (String pair : tokens.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx <= 0 || idx == trimmed.length() - 1) {
                throw new IllegalArgumentException("Malformed wallet.auth.tokens entry: " + trimmed);
            }
            map.put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
        }
        return map;
    }
}
