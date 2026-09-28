package com.mukundagrawal.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** One line of an account statement: an entry plus its transaction's time and description. */
public record StatementLine(long entryId, UUID transactionId, long amount, Instant createdAt, String description) {
}
