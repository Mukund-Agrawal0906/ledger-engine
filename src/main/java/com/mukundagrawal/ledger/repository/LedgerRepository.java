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
import com.mukundagrawal.ledger.domain.Transfer;

/** The append-only log: {@code transactions} + {@code entries}. INSERT and SELECT only (I6). */
@Repository
public class LedgerRepository {

	private final JdbcClient jdbc;

	public LedgerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
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
