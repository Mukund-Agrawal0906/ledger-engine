package com.mukundagrawal.ledger.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.mukundagrawal.ledger.domain.Account;
import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.Statement;
import com.mukundagrawal.ledger.domain.StatementLine;
import com.mukundagrawal.ledger.error.AccountNotFoundException;
import com.mukundagrawal.ledger.repository.AccountRepository;
import com.mukundagrawal.ledger.repository.LedgerRepository;

@Service
public class AccountService {

	private final AccountRepository accounts;

	private final LedgerRepository ledger;

	public AccountService(AccountRepository accounts, LedgerRepository ledger) {
		this.accounts = accounts;
		this.ledger = ledger;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Account createAccount(String name, Currency currency) {
		UUID id = accounts.insertUserAccount(name, currency);
		return accounts.findById(id).orElseThrow();
	}

	@Transactional(readOnly = true)
	public Account getAccount(UUID id) {
		return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
	}

	/** Newest-first page. Fetches {@code limit + 1} rows to know whether another page exists without a COUNT. */
	@Transactional(readOnly = true)
	public Statement getStatement(UUID accountId, Long before, int limit) {
		if (accounts.findById(accountId).isEmpty()) {
			throw new AccountNotFoundException(accountId);
		}
		List<StatementLine> rows = ledger.findEntries(accountId, before, limit + 1);
		if (rows.size() <= limit) {
			return new Statement(rows, null);
		}
		List<StatementLine> page = rows.subList(0, limit);
		return new Statement(List.copyOf(page), page.getLast().entryId());
	}

}
