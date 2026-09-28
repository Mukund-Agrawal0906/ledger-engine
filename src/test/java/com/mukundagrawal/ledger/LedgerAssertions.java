package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Whole-database checks for invariants I1–I4 (docs/CORRECTNESS_INVARIANTS.md). Global on purpose: they
 * must hold no matter which tests ran before, which is why tests never leave half-consistent data.
 * Reused by the Phase 3 stress test.
 */
public final class LedgerAssertions {

	private final JdbcClient jdbc;

	public LedgerAssertions(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void assertAllInvariants() {
		assertEveryTransactionBalances();
		assertMoneyIsConserved();
		assertCachedBalancesMatchLog();
		assertNoUserBalanceIsNegative();
	}

	/** I1: every transaction's entries sum to zero and there are at least two. */
	public void assertEveryTransactionBalances() {
		List<String> bad = jdbc.sql("""
				SELECT t.id::text FROM transactions t
				LEFT JOIN entries e ON e.transaction_id = t.id
				GROUP BY t.id
				HAVING COALESCE(SUM(e.amount), 0) <> 0 OR COUNT(e.id) < 2""")
			.query(String.class)
			.list();
		assertThat(bad).as("I1: unbalanced transactions").isEmpty();
	}

	/** I2: per currency, all balances sum to zero (SYSTEM accounts carry the negative side). */
	public void assertMoneyIsConserved() {
		List<String> bad = jdbc.sql("""
				SELECT a.currency || '=' || SUM(b.balance) FROM account_balances b
				JOIN accounts a ON a.id = b.account_id
				GROUP BY a.currency
				HAVING SUM(b.balance) <> 0""")
			.query(String.class)
			.list();
		assertThat(bad).as("I2: currencies whose balances don't sum to zero").isEmpty();
	}

	/** I3: every cached balance equals the sum of that account's entries. */
	public void assertCachedBalancesMatchLog() {
		List<String> bad = jdbc.sql("""
				SELECT b.account_id::text || ' cached=' || b.balance || ' log=' || COALESCE(e.total, 0)
				  FROM account_balances b
				  LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM entries GROUP BY account_id) e
				         ON e.account_id = b.account_id
				 WHERE b.balance <> COALESCE(e.total, 0)""")
			.query(String.class)
			.list();
		assertThat(bad).as("I3: cached balance != log").isEmpty();
	}

	/** I4: USER accounts are never negative. */
	public void assertNoUserBalanceIsNegative() {
		long negatives = jdbc.sql("SELECT count(*) FROM account_balances WHERE NOT allow_negative AND balance < 0")
			.query(Long.class)
			.single();
		assertThat(negatives).as("I4: negative USER balances").isZero();
	}

}
