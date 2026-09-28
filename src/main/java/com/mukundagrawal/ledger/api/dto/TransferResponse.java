package com.mukundagrawal.ledger.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.Transfer;

public record TransferResponse(UUID id, UUID fromAccountId, UUID toAccountId, long amount, Currency currency,
		String description, Instant createdAt, List<EntryResponse> entries) {

	public static TransferResponse from(Transfer t) {
		return new TransferResponse(t.id(), t.fromAccountId(), t.toAccountId(), t.amount(), t.currency(),
				t.description(), t.createdAt(),
				t.entries().stream().map(e -> new EntryResponse(e.accountId(), e.amount())).toList());
	}

}
