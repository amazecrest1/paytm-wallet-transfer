package com.paytm.wallet.integration;

import com.paytm.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletApiTest extends AbstractIntegrationTest {

    @Test
    void createsWalletWithZeroBalance() {
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/wallets", HttpMethod.POST, new HttpEntity<>(null, authHeaders(TOKEN_ALICE)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("balance_paise", 0);
        assertThat(response.getBody()).containsKey("wallet_id");
    }

    @Test
    void getOrCreateIsIdempotentForSameUser() {
        ResponseEntity<Map> first = restTemplate.exchange(
                baseUrl() + "/wallets", HttpMethod.POST, new HttpEntity<>(null, authHeaders(TOKEN_ALICE)), Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(
                baseUrl() + "/wallets", HttpMethod.POST, new HttpEntity<>(null, authHeaders(TOKEN_ALICE)), Map.class);

        assertThat(first.getBody().get("wallet_id")).isEqualTo(second.getBody().get("wallet_id"));
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets WHERE user_id = 'alice'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void missingBearerTokenIsRejected() {
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl() + "/wallets", null, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getUnknownWalletReturns404() {
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/wallets/" + java.util.UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(null, authHeaders(TOKEN_ALICE)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
