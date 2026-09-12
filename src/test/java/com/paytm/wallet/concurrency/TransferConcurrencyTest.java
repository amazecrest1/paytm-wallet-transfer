package com.paytm.wallet.concurrency;

import com.paytm.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TransferConcurrencyTest extends AbstractIntegrationTest {

    private String createWallet(String token) {
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/wallets", HttpMethod.POST, new HttpEntity<>(null, authHeaders(token)), Map.class);
        return (String) response.getBody().get("wallet_id");
    }

    private void fund(String walletId, long amountPaise) {
        jdbcTemplate.update("UPDATE wallets SET balance_paise = ? WHERE id = ?::uuid", amountPaise, walletId);
    }

    private long balanceOf(String walletId) {
        return jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?::uuid", Long.class, walletId);
    }

    private ResponseEntity<Map> postTransfer(String token, String from, String to, long amountPaise, String key) {
        Map<String, Object> body = Map.of("from", from, "to", to, "amount_paise", amountPaise, "idempotency_key", key);
        return restTemplate.exchange(baseUrl() + "/transfers", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    /**
     * Runs {@code tasks} concurrently through a start-gate so they fire as close to simultaneously as
     * possible, and returns their results in submission order once all have completed.
     */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks, long timeoutSeconds) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 60));
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(tasks.size());
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        return task.call();
                    } finally {
                        doneLatch.countDown();
                    }
                }));
            }
            startGate.countDown();
            boolean finished = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS);
            assertThat(finished).as("all tasks completed within timeout").isTrue();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    results.add(f.get());
                } catch (ExecutionException e) {
                    throw new RuntimeException(e.getCause());
                }
            }
            return results;
        } finally {
            // shutdownNow() (not shutdown()) is load-bearing: if the timeout above is ever hit, some
            // tasks are still blocked holding a DB connection/row lock. A graceful shutdown() would
            // let them keep running in the background, leaking a held lock into the next test's
            // @BeforeEach TRUNCATE and deadlocking IT too. Interrupting here guarantees this test's
            // DB state is fully quiesced before the next test starts.
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void thirtyConcurrentIdenticalTransfersProduceExactlyOneMovement() throws InterruptedException {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 100_000);

        int k = 30;
        List<Callable<ResponseEntity<Map>>> tasks = IntStream.range(0, k)
                .<Callable<ResponseEntity<Map>>>mapToObj(i -> () -> postTransfer(TOKEN_ALICE, from, to, 1_000, "storm-key"))
                .collect(Collectors.toList());

        List<ResponseEntity<Map>> results = runConcurrently(tasks, 30);

        Set<String> distinctTransferIds = results.stream()
                .map(r -> (String) r.getBody().get("transfer_id"))
                .collect(Collectors.toSet());
        Set<Integer> distinctStatuses = results.stream()
                .map(r -> r.getStatusCode().value())
                .collect(Collectors.toSet());

        assertThat(distinctTransferIds).as("all %d responses must be the same transfer", k).hasSize(1);
        assertThat(distinctStatuses).as("all %d responses must carry the same HTTP status", k).hasSize(1);
        assertThat(balanceOf(from)).isEqualTo(99_000); // exactly one debit of 1000, not thirty
        assertThat(balanceOf(to)).isEqualTo(1_000);

        Integer transferRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE idempotency_key = 'storm-key'", Integer.class);
        assertThat(transferRowCount).as("exactly one transfer row for this key").isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyUnder409EvenWhenRacingTheOriginal() throws InterruptedException {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 100_000);

        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> postTransfer(TOKEN_ALICE, from, to, 1_000, "race-key"));
        }
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> postTransfer(TOKEN_ALICE, from, to, 2_000, "race-key")); // different amount, same key
        }

        List<ResponseEntity<Map>> results = runConcurrently(tasks, 30);

        long successCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long conflictCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

        // Exactly one of the two distinct bodies "won" the key; every request with the losing body
        // gets 409, every request with the winning body replays the same success.
        assertThat(successCount + conflictCount).isEqualTo(30);
        assertThat(successCount).isGreaterThan(0);
        assertThat(conflictCount).isGreaterThan(0);

        // Whichever body won, only ITS amount was ever moved — never both, never neither.
        long fromBalance = balanceOf(from);
        assertThat(fromBalance == 99_000 || fromBalance == 98_000)
                .as("exactly one amount (1000 or 2000) was ever debited, balance=%d", fromBalance)
                .isTrue();
    }

    @Test
    void conservationHoldsUnderCrossingContentionWithSomeOverdrawAttempts() throws InterruptedException {
        List<String> wallets = List.of(
                createWallet(TOKEN_ALICE), createWallet(TOKEN_BOB),
                createWallet(TOKEN_CAROL), createWallet(TOKEN_DAVE));
        List<String> tokens = List.of(TOKEN_ALICE, TOKEN_BOB, TOKEN_CAROL, TOKEN_DAVE);
        for (String w : wallets) {
            fund(w, 50_000);
        }
        long totalBefore = wallets.stream().mapToLong(this::balanceOf).sum();

        // 4 wallets -> 6 unordered pairs -> 12 directed (from,to) combinations. Explicitly emitting
        // BOTH directions for every pair is what actually exercises "A->B and B->A at once" (the
        // scenario the deterministic lock order exists to make deadlock-free) — a pure rotation
        // (A->B, B->C, C->A, ...) never revisits a pair in the opposite direction and would not
        // actually test this. Fan-out per directed pair stays modest (burstSize/12) so this proves
        // the invariant without pathological single-row contention, which is a separate concern
        // already covered by insufficientFundsContentionNeverOverdraws.
        int burstSize = 96;
        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        for (int i = 0; i < burstSize; i++) {
            int a = i % 4;
            int b = (a + 1 + (i / 4) % 3) % 4; // cycles through the other 3 wallets as partner
            boolean forward = (i % 2) == 0; // alternate direction on the SAME (a,b) pair
            int fromIdx = forward ? a : b;
            int toIdx = forward ? b : a;
            String from = wallets.get(fromIdx);
            String to = wallets.get(toIdx);
            String token = tokens.get(fromIdx);
            long amount = 500 + (i % 5) * 100; // some will accumulate to exceed balance -> forces declines too
            String key = "burst-" + i;
            tasks.add(() -> postTransfer(token, from, to, amount, key));
        }

        List<ResponseEntity<Map>> results = runConcurrently(tasks, 60);

        long serverErrors = results.stream()
                .filter(r -> r.getStatusCode().is5xxServerError())
                .count();
        assertThat(serverErrors).as("no 5xx under crossing contention (no deadlocks leaking through)").isZero();

        long totalAfter = wallets.stream().mapToLong(this::balanceOf).sum();
        assertThat(totalAfter).as("total balance conserved across %d concurrent transfers", burstSize).isEqualTo(totalBefore);

        for (String w : wallets) {
            assertThat(balanceOf(w)).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void insufficientFundsContentionNeverOverdraws() throws InterruptedException {
        String from = createWallet(TOKEN_ALICE);
        String to = createWallet(TOKEN_BOB);
        fund(from, 10_000); // enough for exactly 10 x 1000 debits

        int attempts = 24;
        List<Callable<ResponseEntity<Map>>> tasks = IntStream.range(0, attempts)
                .<Callable<ResponseEntity<Map>>>mapToObj(i -> () -> postTransfer(TOKEN_ALICE, from, to, 1_000, "od-key-" + i))
                .collect(Collectors.toList());

        // Every one of these requests contends for the SAME wallet pair, so they are genuinely
        // serialized by the lock (by design — that's what "no overdraft under contention" means) —
        // a more generous timeout than the other bursts, which parallelize across independent pairs.
        List<ResponseEntity<Map>> results = runConcurrently(tasks, 90);

        long succeeded = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long declined = results.stream().filter(r -> r.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        long serverErrors = results.stream().filter(r -> r.getStatusCode().is5xxServerError()).count();

        assertThat(serverErrors).isZero();
        assertThat(succeeded).as("exactly 10 of %d concurrent 1000-paise debits can be funded by a 10000 balance", attempts).isEqualTo(10);
        assertThat(declined).isEqualTo(attempts - succeeded);
        assertThat(balanceOf(from)).isEqualTo(0); // drained exactly to zero, never negative
        assertThat(balanceOf(to)).isEqualTo(10_000);
    }
}
