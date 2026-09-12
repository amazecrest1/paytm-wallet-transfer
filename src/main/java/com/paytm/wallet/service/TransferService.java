package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.exception.TransferNotFoundException;
import com.paytm.wallet.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Public entry point for transfers. The actual single-attempt transaction lives in
 * {@link TransferExecutor}; this class wraps it in an OPTIONAL, purely defensive retry.
 * <p>
 * This retry is not part of how correctness is achieved, and removing it would not make the system
 * incorrect — only, in one rare situation, noisier. Correctness (conservation, no-overdraft,
 * exactly-once) comes entirely from {@link TransferExecutor}: deterministic two-step wallet locking,
 * a conditional debit that can only ever run before the credit, and transactional atomicity that
 * guarantees any failure rolls back the whole attempt with nothing partially applied. None of that
 * depends on retrying anything.
 * <p>
 * What the retry is *for*: ordered lock acquisition prevents a wait-cycle between two DIFFERENT
 * transfers touching the same two wallets in opposite roles (A->B racing B->A) — that class of
 * deadlock cannot happen here, retry or no retry. Separately, under very high fan-out contention on
 * the exact SAME wallet pair (many concurrent transfers between the same two wallets), Postgres's own
 * deadlock detector can occasionally abort a transaction purely as an artifact of how it tracks
 * wait-for edges among many simultaneous waiters on one resource — this is documented, expected
 * Postgres behavior under heavy single-row contention, not evidence of a wrong locking strategy. Left
 * unhandled, that abort would surface to the caller as an honest, correct 5xx rather than corrupt any
 * data — nothing would be double-applied or lost. The retry below exists purely to absorb that rare,
 * well-understood case so the caller sees a clean result instead of an occasional transient error; a
 * bounded retry is safe to add here only because a transaction that loses this kind of abort is
 * guaranteed to be rolled back in full by Postgres (idempotency-key insert included), so retrying is
 * indistinguishable from the client resending the same request, which the idempotency design already
 * handles correctly on its own.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int MAX_ATTEMPTS = 10;

    private final TransferExecutor executor;
    private final TransferRepository transferRepository;

    public TransferService(TransferExecutor executor, TransferRepository transferRepository) {
        this.executor = executor;
        this.transferRepository = transferRepository;
    }

    public TransferOutcome transfer(String initiatorUserId, UUID from, UUID to, long amountPaise, String idempotencyKey) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executor.execute(initiatorUserId, from, to, amountPaise, idempotencyKey);
            } catch (PessimisticLockingFailureException deadlockOrLockTimeout) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("transfer_retries_exhausted attempt={} idempotency_key={}", attempt, idempotencyKey);
                    throw deadlockOrLockTimeout;
                }
                log.warn("transfer_retry_after_lock_conflict attempt={} idempotency_key={}", attempt, idempotencyKey);
                backoff(attempt);
            }
        }
    }

    private void backoff(int attempt) {
        // Jittered exponential backoff, capped at 400ms. The jitter (not just the growth) is the part
        // that matters: without it, every waiter retries after the same delay and the whole cohort
        // collides again in lockstep on the next attempt too. The cap is deliberately a few hundred
        // milliseconds, not milliseconds — under real contention a losing transaction needs to wait
        // roughly as long as the transactions ahead of it in the lock queue take to actually finish,
        // and a too-small backoff just re-enters that same queue immediately.
        int maxMillis = Math.min(400, 20 * (1 << attempt));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, maxMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off from a lock conflict", e);
        }
    }

    public Transfer getById(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }
}
