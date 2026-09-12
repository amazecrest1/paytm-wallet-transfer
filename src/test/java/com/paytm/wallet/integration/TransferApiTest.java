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

class TransferApiTest extends AbstractIntegrationTest {

    private String createWallet(String token) {
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/wallets", HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), Map.class);
        return (String) response.getBody().get("wallet_id");
    }

    private void fund(String walletId, long amountPaise) {
        jdbcTemplate.update("UPDATE wallets SET balance_paise = ? WHERE id = ?::uuid", amountPaise, walletId);
    }

    private ResponseEntity<Map> postTransfer(String token, String from, String to, long amountPaise, String key) {
        Map<String, Object> body = Map.of("from", from, "to", to, "amount_paise", amountPaise, "idempotency_key", key);
        return restTemplate.exchange(baseUrl() + "/transfers", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    @Test
    void happyPathMovesMoneyAndConservesTotal() {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 10_000);

        ResponseEntity<Map> response = postTransfer(TOKEN_ALICE, from, to, 4_000, "key-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(balanceOf(from)).isEqualTo(6_000);
        assertThat(balanceOf(to)).isEqualTo(4_000);
    }

    @Test
    void insufficientFundsDeclinesCleanlyWithNoPartialMovement() {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 1_000);

        ResponseEntity<Map> response = postTransfer(TOKEN_ALICE, from, to, 5_000, "key-2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("status", "DECLINED");
        assertThat(response.getBody()).containsEntry("decline_reason", "INSUFFICIENT_FUNDS");
        assertThat(balanceOf(from)).isEqualTo(1_000); // untouched
        assertThat(balanceOf(to)).isEqualTo(0);
    }

    @Test
    void sameKeySameBodyReplaysIdenticalResult() {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 10_000);

        ResponseEntity<Map> first = postTransfer(TOKEN_ALICE, from, to, 2_000, "key-3");
        ResponseEntity<Map> second = postTransfer(TOKEN_ALICE, from, to, 2_000, "key-3");

        assertThat(first.getBody().get("transfer_id")).isEqualTo(second.getBody().get("transfer_id"));
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(balanceOf(from)).isEqualTo(8_000); // moved exactly once
    }

    @Test
    void sameKeyDifferentBodyReturns409() {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 10_000);

        postTransfer(TOKEN_ALICE, from, to, 2_000, "key-4");
        ResponseEntity<Map> conflict = postTransfer(TOKEN_ALICE, from, to, 3_000, "key-4");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(balanceOf(from)).isEqualTo(8_000); // second call must not have touched money
    }

    @Test
    void selfTransferIsRejected() {
        String walletId = createWallet(TOKEN_ALICE);
        fund(walletId, 10_000);

        ResponseEntity<Map> response = postTransfer(TOKEN_ALICE, walletId, walletId, 1_000, "key-5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownDestinationWalletReturns404() {
        String from = createWallet(TOKEN_ALICE);
        fund(from, 10_000);

        ResponseEntity<Map> response = postTransfer(TOKEN_ALICE, from, UUID.randomUUID().toString(), 1_000, "key-6");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonPositiveAmountIsRejected() {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);

        ResponseEntity<Map> response = postTransfer(TOKEN_ALICE, from, to, 0, "key-7");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transferRejectedWhenCallerDoesNotOwnSourceWallet() {
        String aliceWallet = createWallet(TOKEN_ALICE);
        String bobWallet = createWallet(TOKEN_BOB);
        fund(aliceWallet, 10_000);

        // Bob is authenticated as himself but names Alice's wallet as the source.
        ResponseEntity<Map> response = postTransfer(TOKEN_BOB, aliceWallet, bobWallet, 1_000, "key-9");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(aliceWallet)).isEqualTo(10_000); // untouched
        assertThat(balanceOf(bobWallet)).isEqualTo(0);
    }

    @Test
    void ownerInitiatedTransferStillSucceeds() {
        // Companion to the rejection test above: the ownership check must not reject the legitimate path.
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 10_000);

        ResponseEntity<Map> response = postTransfer(TOKEN_ALICE, from, to, 1_000, "key-10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * Regression test for the exact bug caught in design review: an earlier draft tied debit/credit
     * movement order to ascending-wallet-id lock order, which would credit the destination before the
     * source's balance was ever checked whenever the destination happened to sort first — creating
     * money from nothing the instant that debit then failed. The fix fixes movement order to
     * source-debit-then-destination-credit regardless of which wallet's id is lower. Covers BOTH
     * relative orderings deterministically (by inspecting the actual generated ids), not by chance.
     */
    @Test
    void insufficientFundsDeclineNeverCreditsRegardlessOfWalletIdOrdering() {
        assertDeclineLeavesDestinationUntouchedForOrdering(TOKEN_ALICE, TOKEN_BOB, true);  // destination id < source id
        assertDeclineLeavesDestinationUntouchedForOrdering(TOKEN_CAROL, TOKEN_DAVE, false); // destination id > source id
    }

    private void assertDeclineLeavesDestinationUntouchedForOrdering(String tokenX, String tokenY, boolean destinationShouldSortLower) {
        String walletX = createWallet(tokenX);
        String walletY = createWallet(tokenY);

        // Deterministically assign roles from the ACTUAL generated ids so both orderings are covered
        // by construction, not by the luck of random UUID generation.
        boolean xIsLower = walletX.compareTo(walletY) < 0;
        String from, to, fromToken;
        if (destinationShouldSortLower) {
            // destination must be the lower id -> source is whichever is higher.
            from = xIsLower ? walletY : walletX;
            to = xIsLower ? walletX : walletY;
            fromToken = xIsLower ? tokenY : tokenX;
        } else {
            // source must be the lower id -> destination is whichever is higher.
            from = xIsLower ? walletX : walletY;
            to = xIsLower ? walletY : walletX;
            fromToken = xIsLower ? tokenX : tokenY;
        }
        assertThat(to.compareTo(from) < 0).as("destination sorts lower than source").isEqualTo(destinationShouldSortLower);

        fund(from, 1_000); // insufficient for the amount below
        ResponseEntity<Map> response = postTransfer(fromToken, from, to, 5_000, "order-key-" + destinationShouldSortLower);

        assertThat(response.getStatusCode())
                .as("decline must be independent of id ordering (destinationLower=%s)", destinationShouldSortLower)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balanceOf(from)).isEqualTo(1_000); // source untouched (declined before debit could apply)
        assertThat(balanceOf(to)).isEqualTo(0); // destination never credited — the exact property under test
    }

    @Test
    void getTransferStatusReturnsStoredResult() {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 5_000);
        ResponseEntity<Map> created = postTransfer(TOKEN_ALICE, from, to, 1_000, "key-8");
        String transferId = (String) created.getBody().get("transfer_id");

        ResponseEntity<Map> fetched = restTemplate.exchange(baseUrl() + "/transfers/" + transferId,
                HttpMethod.GET, new HttpEntity<>(null, authHeaders(TOKEN_ALICE)), Map.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).containsEntry("status", "COMPLETED");
    }

    private long balanceOf(String walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance_paise FROM wallets WHERE id = ?::uuid", Long.class, walletId);
    }
}
