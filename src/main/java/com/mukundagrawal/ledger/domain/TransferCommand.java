package com.mukundagrawal.ledger.domain;

import java.util.UUID;

/** Service input for a transfer; shape (ranges, lengths) is already validated at the API boundary. */
public record TransferCommand(String idempotencyKey, UUID fromAccountId, UUID toAccountId, long amount,
		Currency currency, String description) {
}
