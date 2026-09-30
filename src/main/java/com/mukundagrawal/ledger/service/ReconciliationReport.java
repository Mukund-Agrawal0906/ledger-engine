package com.mukundagrawal.ledger.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of one reconciliation run. {@code violations} maps an invariant id (I1–I4) to sample offending
 * rows (at most {@link ReconciliationService#SAMPLE_LIMIT} each); an empty list means the invariant holds.
 */
public record ReconciliationReport(Instant checkedAt, Duration duration, Map<String, List<String>> violations) {

	public boolean isClean() {
		return violations.values().stream().allMatch(List::isEmpty);
	}

}
