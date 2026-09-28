package com.mukundagrawal.ledger.service;

import com.mukundagrawal.ledger.domain.Transfer;

/** {@code replayed} is true when an earlier committed transfer was returned for a retried idempotency key. */
public record TransferResult(Transfer transfer, boolean replayed) {
}
