package com.mukundagrawal.ledger.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.mukundagrawal.ledger.domain.Account;
import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.error.AccountNotFoundException;
import com.mukundagrawal.ledger.repository.AccountRepository;

@Service
public class AccountService {

	private final AccountRepository accounts;

	public AccountService(AccountRepository accounts) {
		this.accounts = accounts;
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

}
