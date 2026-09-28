package com.mukundagrawal.ledger.error;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public final class SameAccountException extends LedgerException {

	public SameAccountException(UUID accountId) {
		super("same_account", HttpStatus.UNPROCESSABLE_CONTENT,
				"Cannot transfer from account " + accountId + " to itself.");
	}

}
