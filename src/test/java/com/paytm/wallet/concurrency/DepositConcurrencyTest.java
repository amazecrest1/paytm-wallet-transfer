package com.paytm.wallet.concurrency;

import com.paytm.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deposits share the exact same FK-implied-lock hazard transfers had (see
 * WalletRepository#lockSingle) — many concurrent deposits into ONE wallet all take an implicit FOR
 * KEY SHARE via the deposits FK the instant their row is inserted; without locking first, they'd all
 * need to mutually upgrade past each other's shared lock. This test is the live proof that locking
 * before inserting closes it here too, not just for the two-wallet transfer case.
 */
class DepositConcurrencyTest extends AbstractIntegrationTest {

    private String createWallet(String token) {
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/wallets", HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), Map.class);
        return (String) response.getBody().get("wallet_id");
    }

    private long balanceOf(String walletId) {
        return jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?::uuid", Long.class, walletId);
    }

    private ResponseEntity<Map> postDeposit(String token, String walletId, long amountPaise, String key) {
        Map<String, Object> body = Map.of("amount_paise", amountPaise, "idempotency_key", key);
        return restTemplate.exchange(baseUrl() + "/wallets/" + walletId + "/deposit", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    @Test
    void thirtyConcurrentIdenticalDepositsCreditExactlyOnce() throws InterruptedException {
        String wallet = createWallet(TOKEN_ALICE);

        int k = 30;
        List<Callable<ResponseEntity<Map>>> tasks = IntStream.range(0, k)
                .<Callable<ResponseEntity<Map>>>mapToObj(i -> () -> postDeposit(TOKEN_ALICE, wallet, 1_000, "dep-storm-key"))
                .collect(Collectors.toList());

        List<ResponseEntity<Map>> results = runConcurrently(tasks, 30);

        Set<String> distinctDepositIds = results.stream()
                .map(r -> (String) r.getBody().get("deposit_id"))
                .collect(Collectors.toSet());
        long serverErrors = results.stream().filter(r -> r.getStatusCode().is5xxServerError()).count();

        assertThat(serverErrors).as("no 5xx under same-wallet deposit contention").isZero();
        assertThat(distinctDepositIds).as("all %d responses must be the same deposit", k).hasSize(1);
        assertThat(balanceOf(wallet)).isEqualTo(1_000); // credited exactly once, not thirty times
    }

    @Test
    void fortyConcurrentDistinctDepositsAllLandWithNoLostUpdates() throws InterruptedException {
        String wallet = createWallet(TOKEN_ALICE);

        int n = 40;
        List<Callable<ResponseEntity<Map>>> tasks = IntStream.range(0, n)
                .<Callable<ResponseEntity<Map>>>mapToObj(i -> () -> postDeposit(TOKEN_ALICE, wallet, 100, "dep-" + i))
                .collect(Collectors.toList());

        List<ResponseEntity<Map>> results = runConcurrently(tasks, 30);

        long serverErrors = results.stream().filter(r -> r.getStatusCode().is5xxServerError()).count();
        assertThat(serverErrors).isZero();
        assertThat(balanceOf(wallet)).as("every one of %d distinct concurrent deposits must land", n).isEqualTo(n * 100L);
    }
}
