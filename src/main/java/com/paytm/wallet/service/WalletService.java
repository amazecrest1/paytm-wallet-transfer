package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.exception.WalletNotFoundException;
import com.paytm.wallet.metrics.DomainMetrics;
import com.paytm.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final DomainMetrics metrics;

    public WalletService(WalletRepository walletRepository, DomainMetrics metrics) {
        this.walletRepository = walletRepository;
        this.metrics = metrics;
    }

    /**
     * Race-free get-or-create (Gate 1). No @Transactional here on purpose: the whole operation is a
     * single atomic SQL statement (see WalletRepository#getOrCreate), so there is nothing left for an
     * application-level transaction boundary to protect — wrapping it would only add an extra
     * connection round trip for no correctness benefit.
     */
    public Wallet getOrCreate(String userId) {
        var result = walletRepository.getOrCreate(userId);
        if (result.created()) {
            log.info("wallet_created wallet_id={} user_id={}", result.wallet().id(), userId);
            metrics.walletCreated();
        } else {
            log.info("wallet_get_or_create_hit_existing wallet_id={} user_id={}", result.wallet().id(), userId);
        }
        return result.wallet();
    }

    public Wallet getById(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
