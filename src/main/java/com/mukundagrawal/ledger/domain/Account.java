package com.mukundagrawal.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** Read model: {@code accounts} joined with its {@code account_balances} cache row. */
public record Account(UUID id, String name, AccountKind kind, Currency currency, long balance, long version,
		Instant createdAt) {
}
