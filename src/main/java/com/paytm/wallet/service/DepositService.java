package com.paytm.wallet.service;

import com.paytm.wallet.domain.Deposit;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InvalidRequestException;
import com.paytm.wallet.exception.NotWalletOwnerException;
import com.paytm.wallet.exception.WalletNotFoundException;
import com.paytm.wallet.metrics.DomainMetrics;
import com.paytm.wallet.repository.DepositRepository;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Deposits money into a wallet from OUTSIDE the closed P2P loop (think: a linked bank account). Not
 * part of the exercise's minimum API — added so there is a real way to fund a wallet, since transfers
 * alone can never bootstrap the first balance in the system. Deliberately exempt from "conservation":
 * that invariant is about money neither being created nor destroyed *by a transfer*; a deposit is the
 * explicit, audited, single-wallet exception, exactly like a real bank's external funding source.
 * <p>
 * No separate retry-on-deadlock wrapper (contrast {@link TransferService} around
 * {@link TransferExecutor}): the same FK-implied-lock hazard technically applies to a single wallet
 * under many concurrent deposits, but is closed at the root by locking before inserting (see
 * WalletRepository#lockSingle) rather than papered over by retrying — and unlike two wallets racing
 * from opposite transfer directions, many-way concurrent deposits into one wallet is not a scenario
 * this exercise's burst tests exercise. Adding the two-class retry-wrapper split for that would be
 * complexity out of proportion to the actual risk here.
 */
@Service
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private final WalletRepository walletRepository;
    private final DepositRepository depositRepository;
    private final DomainMetrics metrics;

    public DepositService(WalletRepository walletRepository, DepositRepository depositRepository, DomainMetrics metrics) {
        this.walletRepository = walletRepository;
        this.depositRepository = depositRepository;
        this.metrics = metrics;
    }

    @Transactional
    public Deposit deposit(String userId, UUID walletId, long amountPaise, String idempotencyKey) {
        if (amountPaise <= 0) {
            throw new InvalidRequestException("amount_paise must be positive");
        }

        Wallet wallet = walletRepository.findById(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
        if (!wallet.userId().equals(userId)) {
            throw new NotWalletOwnerException(userId, walletId);
        }

        // Lock BEFORE inserting the deposit row — same reason as transfers (see WalletRepository#lockSingle):
        // the deposits FK into wallets(id) takes an implicit FOR KEY SHARE on insert, and doing that
        // before this explicit lock is what created the N-way deadlock found for transfers.
        walletRepository.lockSingle(walletId);

        String requestHash = RequestHasher.hash(walletId, amountPaise);
        Optional<Deposit> inserted = depositRepository.insertPending(userId, walletId, amountPaise, idempotencyKey, requestHash);

        if (inserted.isEmpty()) {
            return handleIdempotencyConflictOrReplay(userId, idempotencyKey, requestHash);
        }

        Deposit deposit = inserted.get();
        walletRepository.credit(walletId, amountPaise);
        log.info("deposit_completed", kv("event", "deposit_completed"), kv("deposit_id", deposit.id()),
                kv("wallet_id", walletId), kv("amount_paise", amountPaise));
        metrics.depositCompleted();
        return deposit;
    }

    private Deposit handleIdempotencyConflictOrReplay(String userId, String idempotencyKey, String requestHash) {
        Deposit existing = depositRepository.findByUserAndKey(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency row missing after a losing insert — should be unreachable"));

        if (!existing.requestHash().equals(requestHash)) {
            log.info("idempotency_key_conflict", kv("event", "idempotency_key_conflict"),
                    kv("idempotency_key", idempotencyKey), kv("user_id", userId));
            metrics.depositIdempotencyKeyConflict();
            throw new IdempotencyConflictException(idempotencyKey);
        }

        log.info("deposit_idempotent_replay", kv("event", "deposit_idempotent_replay"), kv("deposit_id", existing.id()));
        metrics.depositIdempotentReplay();
        return existing;
    }
}
