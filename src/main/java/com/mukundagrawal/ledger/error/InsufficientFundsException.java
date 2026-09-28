package com.mukundagrawal.ledger.error;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public final class InsufficientFundsException extends LedgerException {

	public InsufficientFundsException(UUID accountId, long balance, long required) {
		super("insufficient_funds", HttpStatus.UNPROCESSABLE_CONTENT,
				"Account " + accountId + " has balance " + balance + ", transfer requires " + required + ".");
	}

}
