package com.mukundagrawal.ledger;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.TransferCommand;
import com.mukundagrawal.ledger.service.AccountService;
import com.mukundagrawal.ledger.service.TransferResult;
import com.mukundagrawal.ledger.service.TransferService;

/**
 * Base for service-level ITs: one shared Spring context + Postgres container. Tests never delete rows
 * (the log is append-only, I6); each test creates its own accounts and asserts relative to them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
public abstract class LedgerITSupport {

	protected static final UUID SYSTEM_INR = UUID.fromString("00000000-0000-0000-0000-000000000001");

	protected static final UUID SYSTEM_USD = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Autowired
	protected AccountService accountService;

	@Autowired
	protected TransferService transferService;

	@Autowired
	protected JdbcClient jdbc;

	protected LedgerAssertions invariants() {
		return new LedgerAssertions(jdbc);
	}

	protected UUID newAccount(Currency currency) {
		return accountService.createAccount("test-" + currency, currency).id();
	}

	/** A USER account funded from the SYSTEM account of its currency. */
	protected UUID newFundedAccount(Currency currency, long amount) {
		UUID id = newAccount(currency);
		if (amount > 0) {
			transfer(currency == Currency.INR ? SYSTEM_INR : SYSTEM_USD, id, amount, currency);
		}
		return id;
	}

	protected TransferResult transfer(UUID from, UUID to, long amount, Currency currency) {
		return transferService.transfer(command(UUID.randomUUID().toString(), from, to, amount, currency));
	}

	protected static TransferCommand command(String key, UUID from, UUID to, long amount, Currency currency) {
		return new TransferCommand(key, from, to, amount, currency, null);
	}

	protected long balance(UUID accountId) {
		return accountService.getAccount(accountId).balance();
	}

	protected boolean keyExists(String key) {
		return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE key = ?").param(key).query(Long.class).single() > 0;
	}

}
