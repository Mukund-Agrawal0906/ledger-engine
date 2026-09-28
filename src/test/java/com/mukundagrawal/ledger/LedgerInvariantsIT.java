package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.error.LedgerException;

/**
 * I1–I4 after a mixed, seeded-random single-threaded workload across two currencies — including
 * transfers that are expected to fail. The concurrent version is the Phase 3 stress test.
 */
class LedgerInvariantsIT extends LedgerITSupport {

	@Test
	void invariantsHoldAfterMixedWorkload() {
		var random = new Random(42);
		List<UUID> inr = Stream.generate(() -> newFundedAccount(Currency.INR, 10_000)).limit(5).toList();
		List<UUID> usd = Stream.generate(() -> newFundedAccount(Currency.USD, 10_000)).limit(3).toList();
		List<UUID> all = Stream.concat(inr.stream(), usd.stream()).toList();

		int committed = 0;
		int rejected = 0;
		for (int i = 0; i < 300; i++) {
			UUID from = all.get(random.nextInt(all.size()));
			UUID to = all.get(random.nextInt(all.size()));
			Currency currency = random.nextBoolean() ? Currency.INR : Currency.USD;
			try {
				transfer(from, to, 1 + random.nextInt(8_000), currency);
				committed++;
			}
			catch (LedgerException expected) {
				rejected++; // same_account / currency_mismatch / insufficient_funds
			}
		}

		// Guard against a vacuous pass: both paths must actually have been exercised.
		assertThat(committed).isPositive();
		assertThat(rejected).isPositive();
		invariants().assertAllInvariants();
	}

}
