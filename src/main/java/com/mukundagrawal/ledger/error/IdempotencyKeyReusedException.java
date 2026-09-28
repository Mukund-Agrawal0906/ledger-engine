package com.mukundagrawal.ledger.error;

import org.springframework.http.HttpStatus;

public final class IdempotencyKeyReusedException extends LedgerException {

	public IdempotencyKeyReusedException(String key) {
		super("idempotency_key_reused", HttpStatus.UNPROCESSABLE_CONTENT,
				"Idempotency-Key \"" + key + "\" was already used for a different request.");
	}

}
