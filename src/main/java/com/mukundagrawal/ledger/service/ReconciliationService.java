package com.mukundagrawal.ledger.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Re-checks invariants I1–I4 (docs/CORRECTNESS_INVARIANTS.md) over the whole database on a schedule.
 * The DB constraints should make violations impossible; this is the detector for the case where they
 * were bypassed (manual SQL, a bad migration, a restore). The same queries back the test assertions,
 * so production detection and test proofs can't drift apart.
 *
 * <p>{@link #check()} has no {@code @Transactional}: each query is a single, self-consistent statement,
 * and when called inside an existing transaction it joins it (used by tests to inspect uncommitted state).
 */
@Service
public class ReconciliationService {

	public static final int SAMPLE_LIMIT = 20;

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	private static final Map<String, String> QUERIES = Map.of(
			// I1: every transaction's entries sum to zero and there are at least two.
			"I1", """
					SELECT t.id::text || ' sum=' || COALESCE(SUM(e.amount), 0) || ' entries=' || COUNT(e.id)
					  FROM transactions t LEFT JOIN entries e ON e.transaction_id = t.id
					 GROUP BY t.id
					HAVING COALESCE(SUM(e.amount), 0) <> 0 OR COUNT(e.id) < 2
					 LIMIT :limit""",
			// I2: per currency, all balances sum to zero (SYSTEM accounts carry the negative side).
			"I2", """
					SELECT a.currency || '=' || SUM(b.balance)
					  FROM account_balances b JOIN accounts a ON a.id = b.account_id
					 GROUP BY a.currency
					HAVING SUM(b.balance) <> 0
					 LIMIT :limit""",
			// I3: every cached balance equals the sum of that account's entries.
			"I3", """
					SELECT b.account_id::text || ' cached=' || b.balance || ' log=' || COALESCE(e.total, 0)
					  FROM account_balances b
					  LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM entries GROUP BY account_id) e
					         ON e.account_id = b.account_id
					 WHERE b.balance <> COALESCE(e.total, 0)
					 LIMIT :limit""",
			// I4: USER accounts are never negative.
			"I4", """
					SELECT account_id::text || ' balance=' || balance
					  FROM account_balances
					 WHERE NOT allow_negative AND balance < 0
					 LIMIT :limit""");

	private final JdbcClient jdbc;

	private volatile ReconciliationReport lastReport;

	public ReconciliationService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public ReconciliationReport check() {
		Instant started = Instant.now();
		Map<String, List<String>> violations = new LinkedHashMap<>();
		for (String invariant : List.of("I1", "I2", "I3", "I4")) {
			violations.put(invariant,
					jdbc.sql(QUERIES.get(invariant)).param("limit", SAMPLE_LIMIT).query(String.class).list());
		}
		return new ReconciliationReport(started, Duration.between(started, Instant.now()), Map.copyOf(violations));
	}

	@Scheduled(initialDelayString = "${ledger.reconciliation.initial-delay:PT30S}",
			fixedDelayString = "${ledger.reconciliation.interval:PT5M}")
	public void runScheduled() {
		ReconciliationReport report = check();
		lastReport = report;
		if (report.isClean()) {
			log.info("Reconciliation clean in {} ms", report.duration().toMillis());
		}
		else {
			log.error("RECONCILIATION FAILED: {}", report.violations());
		}
	}

	/** Empty until the first scheduled run completes. */
	public Optional<ReconciliationReport> lastReport() {
		return Optional.ofNullable(lastReport);
	}

}
