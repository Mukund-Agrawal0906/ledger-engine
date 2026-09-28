package com.mukundagrawal.ledger.error;

import org.springframework.http.HttpStatus;

import com.mukundagrawal.ledger.domain.Currency;

public final class CurrencyMismatchException extends LedgerException {

	public CurrencyMismatchException(Currency requested, Currency from, Currency to) {
		super("currency_mismatch", HttpStatus.UNPROCESSABLE_CONTENT,
				"Transfer currency " + requested + " must match both accounts (from: " + from + ", to: " + to + ").");
	}

}
