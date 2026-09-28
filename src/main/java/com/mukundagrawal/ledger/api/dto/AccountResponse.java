package com.mukundagrawal.ledger.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.mukundagrawal.ledger.domain.Account;
import com.mukundagrawal.ledger.domain.AccountKind;
import com.mukundagrawal.ledger.domain.Currency;

public record AccountResponse(UUID id, String name, AccountKind kind, Currency currency, long balance, long version,
		Instant createdAt) {

	public static AccountResponse from(Account a) {
		return new AccountResponse(a.id(), a.name(), a.kind(), a.currency(), a.balance(), a.version(), a.createdAt());
	}

}
