package com.mukundagrawal.ledger.error;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public final class AccountNotFoundException extends LedgerException {

	public AccountNotFoundException(UUID accountId) {
		super("account_not_found", HttpStatus.NOT_FOUND,
				"Account " + accountId + " does not exist.");
	}

}
