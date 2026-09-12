package com.paytm.wallet.web;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.filter.CurrentUserContext;
import com.paytm.wallet.service.WalletService;
import com.paytm.wallet.web.dto.WalletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
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
}
