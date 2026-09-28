package com.mukundagrawal.ledger.stress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mukundagrawal.ledger.LedgerITSupport;
import com.mukundagrawal.ledger.SqlStates;
import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.TransferCommand;
import com.mukundagrawal.ledger.error.InsufficientFundsException;
import com.mukundagrawal.ledger.service.TransferResult;

/**
 * The headline correctness proof: 51,200 concurrent transfers over a small hot set of accounts, then
 * every invariant (docs/CORRECTNESS_INVARIANTS.md § Stress test). Run with {@code ./mvnw verify -Pstress}.
 */
@Tag("stress")
class ConcurrencyStressIT extends LedgerITSupport {

	static final int USER_ACCOUNTS = 10;
	static final int THREADS = 32;
	static final int PER_THREAD = 1_600;
	static final long OPENING_BALANCE = 1_000_000;
	static final int MAX_AMOUNT = 50_000;
	static final int RETRY_PERCENT = 5;

	record Sent(String key, UUID from, UUID to, long amount) {
	}

	record Committed(String key, UUID transferId, UUID from, UUID to, long amount) {
	}

	@Test
	void fiftyThousandConcurrentTransfersPreserveEveryInvariant() throws Exception {
		List<UUID> accounts = IntStream.range(0, USER_ACCOUNTS)
			.mapToObj(i -> newFundedAccount(Currency.INR, OPENING_BALANCE))
			.toList();
		String prefix = "stress-" + UUID.randomUUID() + "-";

		var committed = new ConcurrentLinkedQueue<Committed>();
		var committedIdByKey = new ConcurrentHashMap<String, UUID>();
		var duplicateCommits = new AtomicInteger(); // I5: a second fresh commit for one key
		var replayMismatches = new AtomicInteger(); // I5: a replay returning a different transfer
		var replayed = new AtomicInteger();
		var insufficient = new AtomicInteger();
		var otherErrors = new ConcurrentHashMap<String, AtomicInteger>(); // keyed by SQLSTATE / exception type
		long[] latencies = new long[THREADS * PER_THREAD];

		// Live I2 sampler: money must be conserved in every committed snapshot, not just at the end.
		var stop = new AtomicBoolean();
		var samples = new AtomicLong();
		var unbalancedSamples = new ConcurrentLinkedQueue<String>();
		Thread sampler = Thread.ofPlatform().name("i2-sampler").start(() -> {
			while (!stop.get()) {
				jdbc.sql("""
						SELECT a.currency || '=' || SUM(b.balance) FROM account_balances b
						JOIN accounts a ON a.id = b.account_id GROUP BY a.currency HAVING SUM(b.balance) <> 0""")
					.query(String.class)
					.list()
					.forEach(unbalancedSamples::add);
				samples.incrementAndGet();
				sleep(50);
			}
		});

		var start = new CountDownLatch(1);
		long began;
		long elapsedNanos;
		try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
			List<Future<?>> workers = new ArrayList<>();
			for (int t = 0; t < THREADS; t++) {
				int thread = t;
				workers.add(pool.submit(() -> {
					var random = new Random(thread); // reproducible per-thread request stream
					List<Sent> history = new ArrayList<>();
					await(start);
					for (int i = 0; i < PER_THREAD; i++) {
						Sent sent;
						if (!history.isEmpty() && random.nextInt(100) < RETRY_PERCENT) {
							sent = history.get(random.nextInt(history.size())); // client retry: same key, same body
						}
						else {
							UUID from = accounts.get(random.nextInt(USER_ACCOUNTS));
							UUID to;
							do {
								to = accounts.get(random.nextInt(USER_ACCOUNTS));
							}
							while (to.equals(from));
							sent = new Sent(prefix + thread + "-" + i, from, to, 1 + random.nextInt(MAX_AMOUNT));
							history.add(sent);
						}

						long t0 = System.nanoTime();
						try {
							TransferResult r = transferService.transfer(new TransferCommand(sent.key(), sent.from(), sent.to(),
									sent.amount(), Currency.INR, null));
							UUID id = r.transfer().id();
							if (r.replayed()) {
								replayed.incrementAndGet();
								if (!id.equals(committedIdByKey.get(sent.key()))) {
									replayMismatches.incrementAndGet();
								}
							}
							else if (committedIdByKey.putIfAbsent(sent.key(), id) != null) {
								duplicateCommits.incrementAndGet();
							}
							else {
								committed.add(new Committed(sent.key(), id, sent.from(), sent.to(), sent.amount()));
							}
						}
						catch (InsufficientFundsException e) {
							insufficient.incrementAndGet();
						}
						catch (Throwable e) {
							String state = SqlStates.of(e);
							otherErrors.computeIfAbsent(state != null ? state : e.getClass().getSimpleName(),
									k -> new AtomicInteger())
								.incrementAndGet();
						}
						finally {
							latencies[thread * PER_THREAD + i] = System.nanoTime() - t0;
						}
					}
				}));
			}
			began = System.nanoTime();
			start.countDown();
			for (Future<?> w : workers) {
				w.get();
			}
			elapsedNanos = System.nanoTime() - began;
		}
		finally {
			stop.set(true);
		}
		sampler.join();

		int total = THREADS * PER_THREAD;
		report(total, committed.size(), replayed.get(), insufficient.get(), otherErrors, latencies, elapsedNanos,
				samples.get());

		// I7(b) + no unexpected failures at all (a deadlock would appear here as 40P01).
		assertThat(otherErrors).as("unexpected errors by SQLSTATE/type").isEmpty();
		// Non-vacuous: the workload really did hit the overdraft race and the retry path.
		assertThat(insufficient.get()).as("insufficient_funds rejections").isPositive();
		assertThat(replayed.get()).as("replayed retries").isPositive();
		assertThat(committed.size() + replayed.get() + insufficient.get()).isEqualTo(total);

		// I2 live.
		assertThat(samples.get()).as("I2 samples taken").isGreaterThan(10);
		assertThat(unbalancedSamples).as("I2: unbalanced snapshots observed during the run").isEmpty();

		// I5.
		assertThat(duplicateCommits.get()).as("I5: keys committed twice").isZero();
		assertThat(replayMismatches.get()).as("I5: replays returning a different transfer").isZero();
		long dbCommittedForRun = jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE key LIKE ?")
			.param(prefix + "%")
			.query(Long.class)
			.single();
		assertThat(dbCommittedForRun).as("I5: committed keys in DB = successes seen by clients").isEqualTo(committed.size());

		// I7(a): recompute every balance client-side from the successful responses — this is what catches a
		// lost update even if some other bug happened to keep the global sum at zero.
		Map<UUID, Long> expected = new HashMap<>();
		accounts.forEach(a -> expected.put(a, OPENING_BALANCE));
		for (Committed c : committed) {
			expected.merge(c.from(), -c.amount(), Long::sum);
			expected.merge(c.to(), c.amount(), Long::sum);
		}
		for (UUID a : accounts) {
			assertThat(balance(a)).as("I7a: balance of %s", a).isEqualTo(expected.get(a));
		}

		// I1–I4 over the whole database.
		invariants().assertAllInvariants();
	}

	private void report(int total, int committed, int replayed, int insufficient, Map<String, AtomicInteger> errors,
			long[] latencies, long elapsedNanos, long samples) {
		double seconds = elapsedNanos / 1e9;
		String md = """
				## Concurrency stress test

				| Metric | Value |
				|---|---|
				| Requests | %,d (%d threads × %,d) over %d hot accounts |
				| Committed transfers | %,d |
				| Replayed retries (idempotent) | %,d |
				| Rejected `insufficient_funds` | %,d |
				| Unexpected errors (incl. deadlocks) | %s |
				| Wall time | %.1f s |
				| Throughput | %,.0f requests/s · %,.0f committed transfers/s |
				| Latency p50 / p95 / p99 / max | %.1f / %.1f / %.1f / %.1f ms |
				| Live I2 snapshots checked | %,d |

				Environment: %s. Service-level calls (no HTTP), Hikari pool 20.
				""".formatted(total, THREADS, PER_THREAD, USER_ACCOUNTS, committed, replayed, insufficient,
				errors.isEmpty() ? "0" : errors.toString(), seconds, total / seconds, committed / seconds,
				StressReport.percentileMs(latencies, 50), StressReport.percentileMs(latencies, 95),
				StressReport.percentileMs(latencies, 99), StressReport.percentileMs(latencies, 100), samples,
				StressReport.environment());
		StressReport.write("stress-report.md", md);
	}

	static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
