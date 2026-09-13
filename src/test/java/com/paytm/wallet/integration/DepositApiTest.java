package com.paytm.wallet.integration;

import com.paytm.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DepositApiTest extends AbstractIntegrationTest {

    private String createWallet(String token) {
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/wallets", HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), Map.class);
        return (String) response.getBody().get("wallet_id");
    }

    private ResponseEntity<Map> postDeposit(String token, String walletId, long amountPaise, String key) {
        Map<String, Object> body = Map.of("amount_paise", amountPaise, "idempotency_key", key);
        return restTemplate.exchange(baseUrl() + "/wallets/" + walletId + "/deposit", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    private long balanceOf(String walletId) {
        return jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?::uuid", Long.class, walletId);
    }

    @Test
    void depositIncreasesBalance() {
        String wallet = createWallet(TOKEN_ALICE);

        ResponseEntity<Map> response = postDeposit(TOKEN_ALICE, wallet, 5_000, "dep-key-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("wallet_id", wallet);
        assertThat(response.getBody()).containsEntry("amount_paise", 5000);
        assertThat(balanceOf(wallet)).isEqualTo(5_000);
    }

    @Test
    void sameKeySameBodyReplaysWithoutDoubleCrediting() {
        String wallet = createWallet(TOKEN_ALICE);

        ResponseEntity<Map> first = postDeposit(TOKEN_ALICE, wallet, 5_000, "dep-key-2");
        ResponseEntity<Map> second = postDeposit(TOKEN_ALICE, wallet, 5_000, "dep-key-2");

        assertThat(first.getBody().get("deposit_id")).isEqualTo(second.getBody().get("deposit_id"));
        assertThat(balanceOf(wallet)).isEqualTo(5_000); // credited exactly once
    }

    @Test
    void sameKeyDifferentAmountReturns409() {
        String wallet = createWallet(TOKEN_ALICE);

        postDeposit(TOKEN_ALICE, wallet, 5_000, "dep-key-3");
        ResponseEntity<Map> conflict = postDeposit(TOKEN_ALICE, wallet, 9_000, "dep-key-3");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(balanceOf(wallet)).isEqualTo(5_000); // second call must not have touched the balance
    }

    @Test
    void depositRejectedWhenCallerDoesNotOwnWallet() {
        String aliceWallet = createWallet(TOKEN_ALICE);

        ResponseEntity<Map> response = postDeposit(TOKEN_BOB, aliceWallet, 5_000, "dep-key-4");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(aliceWallet)).isEqualTo(0);
    }

    @Test
    void nonPositiveAmountIsRejected() {
        String wallet = createWallet(TOKEN_ALICE);

        ResponseEntity<Map> response = postDeposit(TOKEN_ALICE, wallet, 0, "dep-key-5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownWalletReturns404() {
        ResponseEntity<Map> response = postDeposit(TOKEN_ALICE, UUID.randomUUID().toString(), 5_000, "dep-key-6");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void depositedFundsAreImmediatelySpendableViaTransfer() {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        postDeposit(TOKEN_ALICE, from, 10_000, "dep-key-7");

        Map<String, Object> body = Map.of("from", from, "to", to, "amount_paise", 4_000, "idempotency_key", "spend-1");
        ResponseEntity<Map> transfer = restTemplate.exchange(baseUrl() + "/transfers", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(TOKEN_ALICE)), Map.class);

        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balanceOf(from)).isEqualTo(6_000);
        assertThat(balanceOf(to)).isEqualTo(4_000);
    }
}
