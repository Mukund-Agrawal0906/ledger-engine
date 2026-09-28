package com.mukundagrawal.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class LockOrderTest {

	// High bit set in the most-significant half: a negative long to Java, a large unsigned value to Postgres.
	static final UUID HIGH = UUID.fromString("80000000-0000-4000-8000-000000000000");
	static final UUID LOW = UUID.fromString("10000000-0000-4000-8000-000000000000");

	@Test
	void ordersLikePostgresNotLikeUuidCompareTo() {
		// Postgres compares uuid as unsigned bytes: 0x10… < 0x80…
		assertThat(LockOrder.ACCOUNT_ID.compare(LOW, HIGH)).isNegative();

		// The pitfall this class exists to avoid: Java's signed comparison says the opposite.
		assertThat(LOW.compareTo(HIGH)).isPositive();
	}

	@Test
	void sortedIsIndependentOfArgumentOrder() {
		assertThat(LockOrder.sorted(HIGH, LOW)).containsExactly(LOW, HIGH);
		assertThat(LockOrder.sorted(LOW, HIGH)).containsExactly(LOW, HIGH);
	}

}
