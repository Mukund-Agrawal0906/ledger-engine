package com.mukundagrawal.ledger.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.mukundagrawal.ledger.domain.Account;
import com.mukundagrawal.ledger.domain.AccountKind;
import com.mukundagrawal.ledger.domain.Currency;

@Repository
public class AccountRepository {

	private final JdbcClient jdbc;

	public AccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts a USER account and its zero-balance cache row. SYSTEM accounts exist only via migration seed. */
	public UUID insertUserAccount(String name, Currency currency) {
		UUID id = jdbc.sql("INSERT INTO accounts (name, kind, currency) VALUES (:name, 'USER', :currency) RETURNING id")
			.param("name", name)
			.param("currency", currency.name())
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO account_balances (account_id, allow_negative) VALUES (:id, false)")
			.param("id", id)
			.update();
		return id;
	}

	public Optional<Account> findById(UUID id) {
		return jdbc.sql("""
				SELECT a.id, a.name, a.kind, a.currency, a.created_at, b.balance, b.version
				  FROM accounts a
				  JOIN account_balances b ON b.account_id = a.id
				 WHERE a.id = :id""")
			.param("id", id)
			.query(AccountRepository::mapAccount)
			.optional();
	}

	private static Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
		return new Account(rs.getObject("id", UUID.class), rs.getString("name"),
				AccountKind.valueOf(rs.getString("kind")), Currency.valueOf(rs.getString("currency")),
				rs.getLong("balance"), rs.getLong("version"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

}
