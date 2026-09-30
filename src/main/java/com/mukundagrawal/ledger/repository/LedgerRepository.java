package com.mukundagrawal.ledger.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.Entry;
import com.mukundagrawal.ledger.domain.StatementLine;
import com.mukundagrawal.ledger.domain.Transfer;

/** The append-only log: {@code transactions} + {@code entries}. INSERT and SELECT only (I6). */
@Repository
public class LedgerRepository {

	private final JdbcClient jdbc;

	public LedgerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Appends a whole transfer — the transaction row and its debit + credit entries — in ONE statement
	 * (data-modifying CTEs), and returns it, so the hot path needs no separate read-back while locks are
	 * held. The entries' FK to {@code transactions} is checked at end of statement, after both inserts; the
	 * I1 balance trigger still runs at COMMIT.
	 */
	public Transfer appendTransfer(UUID transactionId, UUID from, UUID to, long amount, Currency currency,
			String description) {
		record Row(Instant createdAt, long entryId, UUID accountId, long amount) {
		}
		List<Row> rows = jdbc.sql("""
				WITH t AS (
				    INSERT INTO transactions (id, kind, description)
				    VALUES (:tx, 'TRANSFER', :description)
				    RETURNING id, created_at
				), e AS (
				    INSERT INTO entries (transaction_id, account_id, amount)
				    SELECT t.id, v.account_id, v.amount
				      FROM t, (VALUES (CAST(:from AS uuid), -CAST(:amount AS BIGINT), 1),
				                      (CAST(:to AS uuid), CAST(:amount AS BIGINT), 2)) AS v(account_id, amount, ord)
				     ORDER BY v.ord
				    RETURNING id, account_id, amount
				)
				SELECT (SELECT created_at FROM t) AS created_at, e.id, e.account_id, e.amount
				  FROM e
				 ORDER BY e.id""")
			.param("tx", transactionId)
			.param("description", description)
			.param("from", from)
			.param("to", to)
			.param("amount", amount)
			.query((rs, n) -> new Row(rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getLong("id"),
					rs.getObject("account_id", UUID.class), rs.getLong("amount")))
			.list();
		if (rows.size() != 2) {
			throw new IllegalStateException("Expected 2 entries for transaction " + transactionId + ", got " + rows);
		}
		List<Entry> entries = rows.stream().map(r -> new Entry(r.entryId(), transactionId, r.accountId(), r.amount())).toList();
		return new Transfer(transactionId, from, to, amount, currency, description, rows.getFirst().createdAt(), entries);
	}

	public void insertTransaction(UUID transactionId, String description) {
		jdbc.sql("INSERT INTO transactions (id, kind, description) VALUES (:id, 'TRANSFER', :description)")
			.param("id", transactionId)
			.param("description", description)
			.update();
	}

	/** Debit {@code from}, credit {@code to}. The pair nets to zero, as the commit-time trigger demands (I1). */
	public void insertTransferEntries(UUID transactionId, UUID from, UUID to, long amount) {
		jdbc.sql("""
				INSERT INTO entries (transaction_id, account_id, amount)
				VALUES (:tx, :from, -CAST(:amount AS BIGINT)), (:tx, :to, :amount)""")
			.param("tx", transactionId)
			.param("from", from)
			.param("to", to)
			.param("amount", amount)
			.update();
	}

	/**
	 * Keyset page of an account's entries, newest first: {@code id < before} on the {@code (account_id, id)}
	 * index, so page N costs the same as page 1 (unlike OFFSET).
	 */
	public List<StatementLine> findEntries(UUID accountId, Long before, int limit) {
		return jdbc.sql("""
				SELECT e.id, e.transaction_id, e.amount, t.created_at, t.description
				  FROM entries e
				  JOIN transactions t ON t.id = e.transaction_id
				 WHERE e.account_id = :account
				   AND e.id < COALESCE(CAST(:before AS BIGINT), 9223372036854775807)
				 ORDER BY e.id DESC
				 LIMIT :limit""")
			.param("account", accountId)
			.param("before", before)
			.param("limit", limit)
			.query((rs, n) -> new StatementLine(rs.getLong("id"), rs.getObject("transaction_id", UUID.class),
					rs.getLong("amount"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getString("description")))
			.list();
	}

	/** Rebuilds a transfer from its transaction row and entries (debit first, then credit). */
	public Optional<Transfer> findTransfer(UUID transactionId) {
		record Row(UUID txId, String description, Instant createdAt, long entryId, UUID accountId, long amount,
				Currency currency) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT t.id, t.description, t.created_at, e.id AS entry_id, e.account_id, e.amount, a.currency
				  FROM transactions t
				  JOIN entries e ON e.transaction_id = t.id
				  JOIN accounts a ON a.id = e.account_id
				 WHERE t.id = :id
				 ORDER BY e.id""")
			.param("id", transactionId)
			.query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("description"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getLong("entry_id"),
					rs.getObject("account_id", UUID.class), rs.getLong("amount"),
					Currency.valueOf(rs.getString("currency"))))
			.list();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		if (rows.size() != 2 || rows.get(0).amount() >= 0 || rows.get(1).amount() <= 0) {
			throw new IllegalStateException("Transaction " + transactionId + " is not a two-entry transfer: " + rows);
		}
		Row debit = rows.get(0);
		Row credit = rows.get(1);
		List<Entry> entries = rows.stream().map(r -> new Entry(r.entryId(), r.txId(), r.accountId(), r.amount())).toList();
		return Optional.of(new Transfer(debit.txId(), debit.accountId(), credit.accountId(), credit.amount(),
				debit.currency(), debit.description(), debit.createdAt(), entries));
	}

}
