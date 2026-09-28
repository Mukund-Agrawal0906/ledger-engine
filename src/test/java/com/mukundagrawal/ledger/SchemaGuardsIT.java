package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the DB-level backstops (I1, I4, I6) hold even when the application layer is bypassed
 * entirely. Everything here is raw SQL.
 *
 * <p>The Postgres container is shared with every other IT, so each test either commits a fully
 * consistent posting (entries + matching balance updates) or rolls back — never leaves I2/I3 broken.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaGuardsIT {

	static final UUID SYSTEM_INR = UUID.fromString("00000000-0000-0000-0000-000000000001");
	static final UUID SYSTEM_USD = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate tx;

	@Test
	void seedsOneSystemAccountPerCurrency() {
		var rows = jdbc.sql("""
				SELECT a.currency, b.allow_negative FROM accounts a
				JOIN account_balances b ON b.account_id = a.id
				WHERE a.kind = 'SYSTEM' ORDER BY a.id""")
			.query((rs, n) -> rs.getString(1) + ":" + rs.getBoolean(2))
			.list();
		assertThat(rows).containsExactly("INR:true", "USD:true");
	}

	@Test
	void balancedPostingCommits() {
		UUID alice = createUserAccount("INR");
		UUID txId = postBalanced(SYSTEM_INR, alice, 100);

		long sum = jdbc.sql("SELECT SUM(amount) FROM entries WHERE transaction_id = ?")
			.param(txId)
			.query(Long.class)
			.single();
		assertThat(sum).isZero();
	}

	@Test
	void unbalancedTransactionIsRejectedAtCommit() {
		UUID alice = createUserAccount("INR");
		UUID txId = UUID.randomUUID();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			insertTransaction(txId);
			// This INSERT itself succeeds — the check is deferred to COMMIT.
			insertEntry(txId, alice, 100);
		})).satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo("23514"))
			.hasStackTraceContaining("is unbalanced");

		assertThat(transactionExists(txId)).isFalse();
	}

	@Test
	void transactionWithoutEntriesIsRejectedAtCommit() {
		UUID txId = UUID.randomUUID();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertTransaction(txId)))
			.satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo("23514"));

		assertThat(transactionExists(txId)).isFalse();
	}

	@Test
	void mixedCurrencyTransactionIsRejectedAtCommit() {
		UUID inr = createUserAccount("INR");
		UUID usd = createUserAccount("USD");
		UUID txId = UUID.randomUUID();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			insertTransaction(txId);
			insertEntry(txId, inr, -100);
			insertEntry(txId, usd, 100);
		})).satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo("23514"))
			.hasStackTraceContaining("currencies=2");
	}

	@Test
	void logIsAppendOnly() {
		UUID alice = createUserAccount("INR");
		UUID txId = postBalanced(SYSTEM_INR, alice, 100);

		assertThatThrownBy(() -> jdbc.sql("UPDATE entries SET amount = 999 WHERE transaction_id = ?").param(txId).update())
			.hasStackTraceContaining("append-only");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM entries WHERE transaction_id = ?").param(txId).update())
			.hasStackTraceContaining("append-only");
		assertThatThrownBy(() -> jdbc.sql("UPDATE transactions SET description = 'x' WHERE id = ?").param(txId).update())
			.hasStackTraceContaining("append-only");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM transactions WHERE id = ?").param(txId).update())
			.hasStackTraceContaining("append-only");
		assertThatThrownBy(() -> jdbc.sql("TRUNCATE entries").update())
			.hasStackTraceContaining("append-only");
		assertThatThrownBy(() -> jdbc.sql("TRUNCATE transactions CASCADE").update())
			.hasStackTraceContaining("append-only");

		assertThat(transactionExists(txId)).isTrue();
	}

	@Test
	void userBalanceCannotGoNegative() {
		UUID alice = createUserAccount("INR");

		assertThatThrownBy(() -> jdbc.sql("UPDATE account_balances SET balance = -1 WHERE account_id = ?")
			.param(alice)
			.update())
			.satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo("23514"))
			.hasStackTraceContaining("balance_non_negative_unless_allowed");
	}

	@Test
	void systemBalanceMayGoNegative() {
		// Rolled back: a bare balance change without entries would break I3 for the other tests.
		tx.executeWithoutResult(s -> {
			int updated = jdbc.sql("UPDATE account_balances SET balance = balance - 1 WHERE account_id = ?")
				.param(SYSTEM_USD)
				.update();
			assertThat(updated).isOne();
			s.setRollbackOnly();
		});
	}

	// --- helpers -----------------------------------------------------------------------------

	private UUID createUserAccount(String currency) {
		UUID id = jdbc.sql("INSERT INTO accounts (name, kind, currency) VALUES ('test', 'USER', ?) RETURNING id")
			.param(currency)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO account_balances (account_id, allow_negative) VALUES (?, false)").param(id).update();
		return id;
	}

	/** A complete, consistent posting: transaction + two entries + both cached balances. */
	private UUID postBalanced(UUID from, UUID to, long amount) {
		UUID txId = UUID.randomUUID();
		tx.executeWithoutResult(s -> {
			insertTransaction(txId);
			insertEntry(txId, from, -amount);
			insertEntry(txId, to, amount);
			jdbc.sql("UPDATE account_balances SET balance = balance - ? WHERE account_id = ?").params(amount, from).update();
			jdbc.sql("UPDATE account_balances SET balance = balance + ? WHERE account_id = ?").params(amount, to).update();
		});
		return txId;
	}

	private void insertTransaction(UUID txId) {
		jdbc.sql("INSERT INTO transactions (id, kind) VALUES (?, 'TRANSFER')").param(txId).update();
	}

	private void insertEntry(UUID txId, UUID account, long amount) {
		jdbc.sql("INSERT INTO entries (transaction_id, account_id, amount) VALUES (?, ?, ?)")
			.params(txId, account, amount)
			.update();
	}

	private boolean transactionExists(UUID txId) {
		return jdbc.sql("SELECT count(*) FROM transactions WHERE id = ?").param(txId).query(Long.class).single() > 0;
	}

}
