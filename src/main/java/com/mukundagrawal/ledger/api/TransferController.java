package com.mukundagrawal.ledger.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mukundagrawal.ledger.api.dto.CreateTransferRequest;
import com.mukundagrawal.ledger.api.dto.TransferResponse;
import com.mukundagrawal.ledger.domain.TransferCommand;
import com.mukundagrawal.ledger.error.InvalidIdempotencyKeyException;
import com.mukundagrawal.ledger.service.TransferResult;
import com.mukundagrawal.ledger.service.TransferService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/transfers")
public class TransferController {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	static final String REPLAYED = "Idempotent-Replayed";

	private final TransferService transfers;

	public TransferController(TransferService transfers) {
		this.transfers = transfers;
	}

	/** 201 on first execution; 200 + {@code Idempotent-Replayed: true} with the original body on a retry. */
	@PostMapping
	public ResponseEntity<TransferResponse> create(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
			@Valid @RequestBody CreateTransferRequest request) {
		if (!isValidKey(key)) {
			throw new InvalidIdempotencyKeyException();
		}
		TransferResult result = transfers.transfer(new TransferCommand(key, request.fromAccountId(),
				request.toAccountId(), request.amount(), request.currency(), request.description()));
		TransferResponse body = TransferResponse.from(result.transfer());
		if (result.replayed()) {
			return ResponseEntity.ok().header(REPLAYED, "true").body(body);
		}
		return ResponseEntity.created(URI.create("/transfers/" + body.id())).body(body);
	}

	@GetMapping("/{id}")
	public TransferResponse get(@PathVariable UUID id) {
		return TransferResponse.from(transfers.getTransfer(id));
	}

	/** 1–255 printable ASCII characters (0x20–0x7E), not blank. */
	static boolean isValidKey(String key) {
		return key != null && !key.isBlank() && key.length() <= 255 && key.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
	}

}
