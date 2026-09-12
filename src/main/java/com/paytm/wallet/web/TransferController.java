package com.paytm.wallet.web;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import com.paytm.wallet.filter.CurrentUserContext;
import com.paytm.wallet.service.TransferOutcome;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.web.dto.CreateTransferRequest;
import com.paytm.wallet.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateTransferRequest request) {
        String initiatorUserId = CurrentUserContext.userId();
        TransferOutcome outcome = transferService.transfer(
                initiatorUserId, request.from(), request.to(), request.amountPaise(), request.idempotencyKey());

        // Status code is derived purely from the transfer's final persisted status, never from
        // "did this particular HTTP call do the work or just read it back." That is deliberate: Gate 2
        // requires K concurrent identical-key requests to return identical responses, including the
        // ones that lost the insert race and merely replayed the winner's row — if status depended on
        // "kind" (created vs replayed) instead, otherwise-identical concurrent callers would see
        // different HTTP status codes for the same outcome.
        HttpStatus status = statusFor(outcome.transfer().status());
        return ResponseEntity.status(status).body(TransferResponse.from(outcome.transfer()));
    }

    private HttpStatus statusFor(TransferStatus status) {
        return switch (status) {
            case COMPLETED -> HttpStatus.CREATED;
            case DECLINED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PENDING -> throw new IllegalStateException("Transfer left PENDING outside its own transaction");
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getById(@PathVariable UUID id) {
        Transfer transfer = transferService.getById(id);
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }
}
