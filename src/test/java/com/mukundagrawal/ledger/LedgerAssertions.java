package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.mukundagrawal.ledger.service.ReconciliationReport;
import com.mukundagrawal.ledger.service.ReconciliationService;

/**
 * Whole-database assertions for invariants I1–I4 (docs/CORRECTNESS_INVARIANTS.md). Global on purpose: they
 * must hold no matter which tests ran before, which is why tests never leave half-consistent data.
 *
 * <p>Delegates to the production {@link ReconciliationService}, so the SQL that proves the invariants in
 * tests is exactly the SQL that watches for them in production.
 */
public final class LedgerAssertions {

	private final ReconciliationService reconciliation;

	public LedgerAssertions(JdbcClient jdbc) {
		this.reconciliation = new ReconciliationService(jdbc);
	}

	public void assertAllInvariants() {
		ReconciliationReport report = reconciliation.check();
		assertThat(report.violations().get("I1")).as("I1: unbalanced transactions").isEmpty();
		assertThat(report.violations().get("I2")).as("I2: currencies whose balances don't sum to zero").isEmpty();
		assertThat(report.violations().get("I3")).as("I3: cached balance != log").isEmpty();
		assertThat(report.violations().get("I4")).as("I4: negative USER balances").isEmpty();
	}

}
