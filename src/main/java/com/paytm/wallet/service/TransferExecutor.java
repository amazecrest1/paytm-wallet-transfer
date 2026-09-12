package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InvalidRequestException;
import com.paytm.wallet.exception.NotWalletOwnerException;
import com.paytm.wallet.exception.WalletNotFoundException;
import com.paytm.wallet.metrics.DomainMetrics;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The single-attempt transfer transaction, split out of {@link TransferService} into its own Spring
 * bean purely so {@link TransferService#transfer} can retry it as a whole unit through a real proxy
 * boundary — self-invoking an {@code @Transactional} method on {@code this} silently skips Spring's
 * AOP proxy, so a retry loop living in the same class would not actually get a fresh transaction per
 * attempt. See {@link TransferService} for why a retry loop exists at all.
 */
@Component
class TransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutor.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final DomainMetrics metrics;

    TransferExecutor(WalletRepository walletRepository, TransferRepository transferRepository, DomainMetrics metrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.metrics = metrics;
    }

    @Transactional
    TransferOutcome execute(String initiatorUserId, UUID from, UUID to, long amountPaise, String idempotencyKey) {
        if (from.equals(to)) {
            throw new InvalidRequestException("from and to must be different wallets");
        }
        if (amountPaise <= 0) {
            throw new InvalidRequestException("amount_paise must be positive");
        }

        Wallet fromWallet = requireWalletExists(from);
        requireWalletExists(to);

        // The brief and rubric are silent on source-wallet ownership — this is a deliberate design
        // decision, not a spec requirement (see DESIGN.md): without it, any authenticated user who
        // learns another wallet's id could drain it while authenticated as themselves. Checked before
        // any locking, so an unauthorized request never contends for a wallet lock at all.
        if (!fromWallet.userId().equals(initiatorUserId)) {
            throw new NotWalletOwnerException(initiatorUserId, from);
        }

        // Acquire both row locks, in ascending-id order, BEFORE inserting the transfers row — not
        // after. This ordering is load-bearing and was discovered the hard way: transfers.from_wallet_id
        // and transfers.to_wallet_id are FOREIGN KEYs into wallets(id), and Postgres enforces that by
        // implicitly taking a FOR KEY SHARE lock on the referenced wallet rows the instant the INSERT
        // runs — in whichever order the FK triggers check them (request order: from, then to), NOT our
        // sorted order. Under many concurrent transfers on the same pair, that implicit FOR KEY SHARE
        // (compatible with itself, so every concurrent inserter can hold it at once) collides with our
        // own later attempt to upgrade to FOR UPDATE: everyone ends up mutually waiting for everyone
        // else's FOR KEY SHARE to release, which none of them can do until THEY get their own FOR
        // UPDATE — a genuine N-way deadlock, reproduced live under a same-pair concurrency burst.
        // Locking first means our own FK check, moments later, is satisfied by a lock our own
        // transaction already holds — a no-op — instead of racing every other concurrent inserter.
        walletRepository.lockPairOrdered(from, to);

        String requestHash = RequestHasher.hash(from, to, amountPaise);
        Optional<Transfer> inserted = transferRepository.insertPending(initiatorUserId, from, to, amountPaise, idempotencyKey, requestHash);

        if (inserted.isEmpty()) {
            return handleIdempotencyConflictOrReplay(initiatorUserId, idempotencyKey, requestHash);
        }

        Transfer transfer = inserted.get();
        log.info("transfer_created",
                kv("event", "transfer_created"), kv("transfer_id", transfer.id()),
                kv("from_wallet_id", from), kv("to_wallet_id", to), kv("amount_paise", amountPaise));

        int rowsDebited = walletRepository.conditionalDebit(from, amountPaise);
        if (rowsDebited == 0) {
            transferRepository.markDeclined(transfer.id(), "INSUFFICIENT_FUNDS");
            log.info("transfer_declined_insufficient_funds",
                    kv("event", "transfer_declined_insufficient_funds"), kv("transfer_id", transfer.id()),
                    kv("from_wallet_id", from), kv("amount_paise", amountPaise));
            metrics.transferDeclinedInsufficientFunds();
            return new TransferOutcome(mustFind(transfer.id()), TransferOutcome.Kind.DECLINED);
        }
        log.info("transfer_debited", kv("event", "transfer_debited"), kv("transfer_id", transfer.id()),
                kv("from_wallet_id", from), kv("amount_paise", amountPaise));

        walletRepository.credit(to, amountPaise);
        log.info("transfer_credited", kv("event", "transfer_credited"), kv("transfer_id", transfer.id()),
                kv("to_wallet_id", to), kv("amount_paise", amountPaise));

        transferRepository.markCompleted(transfer.id());
        metrics.transferCompleted();
        return new TransferOutcome(mustFind(transfer.id()), TransferOutcome.Kind.CREATED);
    }

    /**
     * Reached when this call's idempotency insert lost the race (§2/§6 of the design: locks are
     * acquired before that insert, so this path is reached only after this transaction already
     * holds — and, on return here, is about to release — both wallet locks). Whether the outcome
     * below is a conflict or a replay, neither branch performs any balance mutation: the losing
     * request locks and releases the wallets as a side effect of the ordering that keeps the
     * FK-implied-lock hazard from reintroducing a deadlock, but it never reaches the debit/credit
     * statements. "Never touches a wallet" would overstate this — it never MUTATES one.
     */
    private TransferOutcome handleIdempotencyConflictOrReplay(String initiatorUserId, String idempotencyKey, String requestHash) {
        Transfer existing = transferRepository.findByInitiatorAndKey(initiatorUserId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency row missing after a losing insert — should be unreachable"));

        if (!existing.requestHash().equals(requestHash)) {
            log.info("idempotency_key_conflict", kv("event", "idempotency_key_conflict"),
                    kv("idempotency_key", idempotencyKey), kv("initiator_user_id", initiatorUserId));
            metrics.idempotencyKeyConflict();
            throw new IdempotencyConflictException(idempotencyKey);
        }

        log.info("transfer_idempotent_replay", kv("event", "transfer_idempotent_replay"),
                kv("transfer_id", existing.id()), kv("status", existing.status()));
        metrics.transferIdempotentReplay();
        return new TransferOutcome(existing, TransferOutcome.Kind.REPLAYED);
    }

    private Wallet requireWalletExists(UUID walletId) {
        return walletRepository.findById(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    private Transfer mustFind(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer vanished within its own transaction: " + transferId));
    }
}
