package com.mukundagrawal.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A committed transfer, reconstructed from a {@code transactions} row and its two entries. There is no
 * separate transfers table, so this view cannot drift from the log.
 */
public record Transfer(UUID id, UUID fromAccountId, UUID toAccountId, long amount, Currency currency,
		String description, Instant createdAt, List<Entry> entries) {
}
