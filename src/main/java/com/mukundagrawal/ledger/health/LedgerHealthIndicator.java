package com.mukundagrawal.ledger.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.mukundagrawal.ledger.service.ReconciliationReport;
import com.mukundagrawal.ledger.service.ReconciliationService;

/**
 * {@code /actuator/health} component {@code ledger}: the result of the last scheduled reconciliation.
 * Never runs the (full-scan) queries itself, so health polling stays cheap.
 *
 * <p>Deliberately not part of the liveness probe: a data-integrity alarm needs a human, and restarting the
 * app would not fix it.
 */
@Component("ledger")
public class LedgerHealthIndicator implements HealthIndicator {

	private final ReconciliationService reconciliation;

	public LedgerHealthIndicator(ReconciliationService reconciliation) {
		this.reconciliation = reconciliation;
	}

	@Override
	public Health health() {
		return reconciliation.lastReport().map(LedgerHealthIndicator::toHealth)
			.orElseGet(() -> Health.unknown().withDetail("reconciliation", "not run yet").build());
	}

	static Health toHealth(ReconciliationReport report) {
		Health.Builder builder = report.isClean() ? Health.up() : Health.down();
		builder.withDetail("lastRun", report.checkedAt().toString())
			.withDetail("durationMs", report.duration().toMillis());
		report.violations().forEach((invariant, samples) -> {
			if (!samples.isEmpty()) {
				builder.withDetail(invariant, samples);
			}
		});
		return builder.build();
	}

}
