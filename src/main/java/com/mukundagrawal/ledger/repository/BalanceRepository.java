package com.mukundagrawal.ledger.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
	 * Locks both balance rows in ONE round trip, held until the surrounding transaction ends. Missing
	 * accounts are simply absent from the result.
	 *
	 * <p>Deadlock-freedom: Postgres takes the row locks in {@code ORDER BY} order (the LockRows plan node
	 * sits above the Sort), so every transfer acquires its locks in ascending {@code uuid} order — the same
	 * global order as {@link com.mukundagrawal.ledger.service.LockOrder}. {@code FOR UPDATE OF b} locks only
	 * the balance rows, never the {@code accounts} metadata rows.
	 */
	public Map<UUID, LockedBalance> lockBoth(UUID a, UUID b) {
		List<LockedBalance> rows = jdbc.sql("""
				SELECT b.account_id, b.balance, b.allow_negative, a.currency
				  FROM account_balances b
				  JOIN accounts a ON a.id = b.account_id
				 WHERE b.account_id IN (:a, :b)
				 ORDER BY b.account_id
				   FOR UPDATE OF b""")
			.param("a", a)
			.param("b", b)
			.query((rs, n) -> new LockedBalance(rs.getObject("account_id", UUID.class), rs.getLong("balance"),
					rs.getBoolean("allow_negative"), Currency.valueOf(rs.getString("currency"))))
			.list();
		return rows.stream().collect(Collectors.toMap(LockedBalance::accountId, Function.identity()));
	}

	/** Debits {@code from} and credits {@code to} in ONE statement; both rows must already be locked by the caller. */
	public void applyTransfer(UUID from, UUID to, long amount) {
		int updated = jdbc.sql("""
				UPDATE account_balances b
				   SET balance = b.balance + v.delta, version = b.version + 1, updated_at = now()
				  FROM (VALUES (CAST(:from AS uuid), -CAST(:amount AS BIGINT)),
				               (CAST(:to AS uuid), CAST(:amount AS BIGINT))) AS v(account_id, delta)
				 WHERE b.account_id = v.account_id""")
			.param("from", from)
			.param("to", to)
			.param("amount", amount)
			.update();
		if (updated != 2) {
			throw new IllegalStateException("Expected to update 2 balance rows for " + from + " -> " + to + ", updated " + updated);
		}
	}

}
