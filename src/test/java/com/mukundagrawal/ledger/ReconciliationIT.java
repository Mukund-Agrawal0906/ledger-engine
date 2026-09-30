package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.service.ReconciliationReport;
import com.mukundagrawal.ledger.service.ReconciliationService;

class ReconciliationIT extends LedgerITSupport {

	@Autowired
	ReconciliationService reconciliation;

	@Autowired
	TransactionTemplate tx;

	@Test
	void cleanLedgerHasNoViolations() {
		UUID alice = newFundedAccount(Currency.INR, 1_000);
		transfer(alice, newAccount(Currency.INR), 400, Currency.INR);

		ReconciliationReport report = reconciliation.check();

		assertThat(report.isClean()).as("violations: %s", report.violations()).isTrue();
		assertThat(report.violations()).containsOnlyKeys("I1", "I2", "I3", "I4");
	}

	/**
	 * Corrupts a cached balance behind the ledger's back (bypassing the log), then checks detection. Runs
	 * inside a transaction that is rolled back, so the shared database is never left corrupted.
	 */
	@Test
	void detectsCachedBalanceDriftingFromTheLog() {
		UUID alice = newFundedAccount(Currency.INR, 1_000);

		tx.executeWithoutResult(status -> {
			jdbc.sql("UPDATE account_balances SET balance = balance + 7 WHERE account_id = ?").param(alice).update();

			ReconciliationReport report = reconciliation.check(); // joins this transaction, sees the corruption

			assertThat(report.isClean()).isFalse();
			assertThat(report.violations().get("I3")).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
				.contains(alice.toString())
				.contains("cached=1007")
				.contains("log=1000");
			assertThat(report.violations().get("I2")).as("money appeared from nowhere").singleElement(
					org.assertj.core.api.InstanceOfAssertFactories.STRING).isEqualTo("INR=7");
			status.setRollbackOnly();
		});

		assertThat(reconciliation.check().isClean()).as("rolled back").isTrue();
	}

}
