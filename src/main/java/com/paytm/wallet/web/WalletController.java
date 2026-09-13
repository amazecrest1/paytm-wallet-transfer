package com.paytm.wallet.web;

import com.paytm.wallet.domain.Deposit;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.filter.CurrentUserContext;
import com.paytm.wallet.service.DepositService;
import com.paytm.wallet.service.WalletService;
import com.paytm.wallet.web.dto.DepositRequest;
import com.paytm.wallet.web.dto.DepositResponse;
import com.paytm.wallet.web.dto.WalletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;
    private final DepositService depositService;

    public WalletController(WalletService walletService, DepositService depositService) {
        this.walletService = walletService;
        this.depositService = depositService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreate() {
        String userId = CurrentUserContext.userId();
        Wallet wallet = walletService.getOrCreate(userId);
        // 201 vs 200 is cosmetic here (get-or-create is intentionally idempotent either way); we
        // return 201 unconditionally to keep the response contract simple, matching the brief's
        // framing of this endpoint as "get-or-create" rather than a strict resource-creation POST.
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getById(@PathVariable UUID id) {
        Wallet wallet = walletService.getById(id);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    /**
     * Not part of the exercise's minimum API — added so a wallet can be funded through the public API
     * at all (transfers alone can never bootstrap the system's first balance). Deliberately restricted
     * to the wallet's own owner, symmetric with the transfer authorization decision (DESIGN.md): money
     * only enters or leaves a wallet at its owner's initiative.
     */
    @PostMapping("/{id}/deposit")
    public ResponseEntity<DepositResponse> deposit(@PathVariable UUID id, @Valid @RequestBody DepositRequest request) {
        String userId = CurrentUserContext.userId();
        Deposit deposit = depositService.deposit(userId, id, request.amountPaise(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(DepositResponse.from(deposit));
    }
}
