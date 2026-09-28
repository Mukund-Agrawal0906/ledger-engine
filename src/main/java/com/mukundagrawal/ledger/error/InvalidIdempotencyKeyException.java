package com.mukundagrawal.ledger.error;

import org.springframework.http.HttpStatus;

public final class InvalidIdempotencyKeyException extends LedgerException {

	public InvalidIdempotencyKeyException() {
		super("missing_idempotency_key", HttpStatus.BAD_REQUEST,
				"POST /transfers requires an Idempotency-Key header of 1-255 printable ASCII characters.");
	}

}
