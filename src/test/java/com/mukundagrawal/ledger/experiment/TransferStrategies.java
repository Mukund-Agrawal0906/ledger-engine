package com.mukundagrawal.ledger.experiment;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.mukundagrawal.ledger.SqlStates;
import com.mukundagrawal.ledger.error.InsufficientFundsException;
import com.mukundagrawal.ledger.repository.LedgerRepository;
import com.mukundagrawal.ledger.service.LockOrder;

/**
 * Deliberately weaker implementations of the balance step, for the isolation experiment only — never
 * production code. Each writes a correct, balanced log (so I1 holds); what differs is how the cached
 * balance is read, checked and written.
 *
 * <p>All variants write the two balance rows in {@link LockOrder}, so the experiment isolates the
 * isolation-level question from the lock-ordering one. (A first run with from→to write order deadlocked
 * on 35–40% of attempts in A/A2 — each costing Postgres's 1 s deadlock_timeout.)
 */
final class TransferStrategies {

	interface Strategy {

		/** Returns the number of serialization/deadlock retries it needed (0 for non-retrying strategies). */
		int transfer(UUID from, UUID to, long amount);

	}

	private record Row(long balance, boolean allowNegative) {
	}

	private final JdbcClient jdbc;

	private final LedgerRepository ledger;

	private final TransactionTemplate readCommitted;

	private final TransactionTemplate serializable;

	TransferStrategies(JdbcClient jdbc, LedgerRepository ledger, PlatformTransactionManager txManager) {
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.readCommitted = template(txManager, TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.serializable = template(txManager, TransactionDefinition.ISOLATION_SERIALIZABLE);
	}

	/** A: unlocked read → compute in Java → write the absolute value. Classic lost update. */
	Strategy naiveReadModifyWrite() {
		return (from, to, amount) -> {
			readCommitted.executeWithoutResult(s -> readModifyWrite(from, to, amount));
			return 0;
		};
	}

	/** A2: unlocked funds check, but atomic in-place increments. No lost updates; check-then-act race remains. */
	Strategy atomicIncrementUnlockedCheck() {
		return (from, to, amount) -> {
			readCommitted.executeWithoutResult(s -> {
				Row src = read(from);
				if (!src.allowNegative() && src.balance() < amount) {
					throw new InsufficientFundsException(from, src.balance(), amount);
				}
				appendLog(from, to, amount);
				for (UUID id : LockOrder.sorted(from, to)) {
					increment(id, id.equals(from) ? -amount : amount);
				}
			});
			return 0;
		};
	}

	/** C: same SQL as A, but SERIALIZABLE — Postgres SSI aborts the anomalies (40001) and we retry. */
	Strategy serializableWithRetry(int maxAttempts) {
		return (from, to, amount) -> {
			for (int attempt = 1;; attempt++) {
				try {
					serializable.executeWithoutResult(s -> readModifyWrite(from, to, amount));
					return attempt - 1;
				}
				catch (RuntimeException e) {
					String state = SqlStates.of(e);
					boolean retryable = "40001".equals(state) || "40P01".equals(state);
					if (!retryable || attempt >= maxAttempts) {
						throw e;
					}
				}
			}
		};
	}

	private void readModifyWrite(UUID from, UUID to, long amount) {
		Row src = read(from);
		Row dst = read(to);
		if (!src.allowNegative() && src.balance() < amount) {
			throw new InsufficientFundsException(from, src.balance(), amount);
		}
		appendLog(from, to, amount);
		for (UUID id : LockOrder.sorted(from, to)) {
			setAbsolute(id, id.equals(from) ? src.balance() - amount : dst.balance() + amount);
		}
	}

	private Row read(UUID id) {
		return jdbc.sql("SELECT balance, allow_negative FROM account_balances WHERE account_id = ?")
			.param(id)
			.query((rs, n) -> new Row(rs.getLong(1), rs.getBoolean(2)))
			.single();
	}

	private void appendLog(UUID from, UUID to, long amount) {
		UUID txId = UUID.randomUUID();
		ledger.insertTransaction(txId, "experiment");
		ledger.insertTransferEntries(txId, from, to, amount);
	}

	private void setAbsolute(UUID id, long balance) {
		jdbc.sql("UPDATE account_balances SET balance = ?, version = version + 1 WHERE account_id = ?")
			.params(balance, id)
			.update();
	}

	private void increment(UUID id, long delta) {
		jdbc.sql("UPDATE account_balances SET balance = balance + ?, version = version + 1 WHERE account_id = ?")
			.params(delta, id)
			.update();
	}

	private static TransactionTemplate template(PlatformTransactionManager txManager, int isolation) {
		var t = new TransactionTemplate(txManager);
		t.setIsolationLevel(isolation);
		return t;
	}

}
