package com.mukundagrawal.ledger.domain;

import java.util.UUID;

/** One ledger line. Negative = value leaves the account, positive = value arrives. */
public record Entry(long id, UUID transactionId, UUID accountId, long amount) {
}
