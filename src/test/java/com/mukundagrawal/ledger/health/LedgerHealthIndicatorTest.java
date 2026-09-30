package com.mukundagrawal.ledger.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.mukundagrawal.ledger.service.ReconciliationReport;
import com.mukundagrawal.ledger.service.ReconciliationService;

class LedgerHealthIndicatorTest {

	static final Instant AT = Instant.parse("2026-09-30T10:00:00Z");

	static final Map<String, List<String>> CLEAN = Map.of("I1", List.of(), "I2", List.of(), "I3", List.of(), "I4", List.of());

	final ReconciliationService reconciliation = mock(ReconciliationService.class);

	final LedgerHealthIndicator indicator = new LedgerHealthIndicator(reconciliation);

	@Test
	void unknownBeforeFirstRun() {
		when(reconciliation.lastReport()).thenReturn(Optional.empty());
		assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
	}

	@Test
	void upWhenLastRunWasClean() {
		when(reconciliation.lastReport()).thenReturn(Optional.of(new ReconciliationReport(AT, Duration.ofMillis(42), CLEAN)));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("lastRun", "2026-09-30T10:00:00Z").containsEntry("durationMs", 42L)
			.doesNotContainKeys("I1", "I2", "I3", "I4");
	}

	@Test
	void downWithSamplesWhenAnInvariantIsViolated() {
		var violations = new java.util.HashMap<>(CLEAN);
		violations.put("I3", List.of("abc cached=1007 log=1000"));
		when(reconciliation.lastReport()).thenReturn(Optional.of(new ReconciliationReport(AT, Duration.ZERO, violations)));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.getDetails()).containsEntry("I3", List.of("abc cached=1007 log=1000")).doesNotContainKey("I1");
	}

}
