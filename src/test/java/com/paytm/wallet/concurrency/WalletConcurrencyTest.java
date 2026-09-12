package com.paytm.wallet.concurrency;

import com.paytm.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 1: N truly concurrent POST /wallets for one user must produce exactly one wallet row.
 * <p>
 * This does NOT just call the service method in a loop — it opens real concurrent HTTP connections
 * through the full filter chain into real Postgres, and uses a CountDownLatch start gate so every
 * thread fires as close to simultaneously as the JVM allows, which is what actually gives the naive
 * check-then-insert anti-pattern a chance to lose the race (a sequential loop never would).
 */
class WalletConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void fiftyConcurrentGetOrCreateProduceExactlyOneWallet() throws InterruptedException {
        int concurrency = 50;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        List<Future<String>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            Future<String> future = pool.submit(() -> {
                startGate.await();
                try {
                    ResponseEntity<Map> response = restTemplate.exchange(
                            baseUrl() + "/wallets", HttpMethod.POST,
                            new HttpEntity<>(null, authHeaders(TOKEN_ALICE)), Map.class);
                    return (String) response.getBody().get("wallet_id");
                } finally {
                    doneLatch.countDown();
                }
            });
            futures.add(future);
        }

        startGate.countDown(); // release all 50 at once
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(finished).as("all 50 requests completed in time").isTrue();

        Set<String> distinctWalletIds = futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toSet());

        assertThat(distinctWalletIds).as("all 50 responses must name the same wallet").hasSize(1);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE user_id = 'alice'", Integer.class);
        assertThat(rowCount).as("exactly one row in the DB, not just in the responses").isEqualTo(1);
    }
}
