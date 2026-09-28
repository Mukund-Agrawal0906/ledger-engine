package com.mukundagrawal.ledger.error;

import org.springframework.http.HttpStatus;

/**
 * Base for every expected business-rule failure. Each carries the stable machine-readable {@code code}
 * and HTTP status from the error catalog in docs/API_CONTRACTS.md §3. Anything that is not a
 * {@code LedgerException} is a bug and surfaces as {@code internal_error}.
 */
public abstract sealed class LedgerException extends RuntimeException
		permits AccountNotFoundException, TransferNotFoundException, SameAccountException, CurrencyMismatchException,
		InsufficientFundsException, IdempotencyKeyReusedException {

	private final String code;

	private final HttpStatus status;

	protected LedgerException(String code, HttpStatus status, String detail) {
		super(detail);
		this.code = code;
		this.status = status;
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}

}
