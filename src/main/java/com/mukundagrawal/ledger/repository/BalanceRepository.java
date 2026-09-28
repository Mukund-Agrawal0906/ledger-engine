package com.mukundagrawal.ledger.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.mukundagrawal.ledger.domain.Currency;

/** The hot path: row locks and in-place balance updates on {@code account_balances} (docs/LLD.md §3–4). */
@Repository
public class BalanceRepository {

	/** A balance row as read while holding its lock — safe to base the funds check on. */
	public record LockedBalance(UUID accountId, long balance, boolean allowNegative, Currency currency) {
	}

	private final JdbcClient jdbc;

	public BalanceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks one balance row until the surrounding transaction ends. {@code FOR UPDATE OF b} locks only
	 * the balance row, never the {@code accounts} metadata row. Callers lock multiple rows in
	 * {@link com.mukundagrawal.ledger.service.LockOrder} order.
	 */
	public Optional<LockedBalance> lockForUpdate(UUID accountId) {
		return jdbc.sql("""
				SELECT b.account_id, b.balance, b.allow_negative, a.currency
				  FROM account_balances b
				  JOIN accounts a ON a.id = b.account_id
				 WHERE b.account_id = :id
				   FOR UPDATE OF b""")
			.param("id", accountId)
			.query((rs, n) -> new LockedBalance(rs.getObject("account_id", UUID.class), rs.getLong("balance"),
					rs.getBoolean("allow_negative"), Currency.valueOf(rs.getString("currency"))))
			.optional();
	}

	/** Applies a signed delta to a row the caller already holds locked. */
	public void applyDelta(UUID accountId, long delta) {
		int updated = jdbc.sql("""
				UPDATE account_balances
				   SET balance = balance + :delta, version = version + 1, updated_at = now()
				 WHERE account_id = :id""")
			.param("delta", delta)
			.param("id", accountId)
			.update();
		if (updated != 1) {
			throw new IllegalStateException("Expected to update 1 balance row for " + accountId + ", updated " + updated);
		}
	}

}
