package com.mukundagrawal.ledger.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.mukundagrawal.ledger.SqlStates;
import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.TransferCommand;
import com.mukundagrawal.ledger.error.InsufficientFundsException;
import com.mukundagrawal.ledger.repository.LedgerRepository;
import com.mukundagrawal.ledger.service.AccountService;
import com.mukundagrawal.ledger.service.TransferService;
import com.mukundagrawal.ledger.stress.StressReport;

/**
 * Shows the anomalies instead of just claiming immunity (docs/CORRECTNESS_INVARIANTS.md § Isolation
 * experiment): the same contended workload against four ways of doing the balance step.
 */
@Tag("stress")
@SpringBootTest
@Import(ExperimentContainerConfig.class)
class IsolationExperimentIT {

	static final UUID SYSTEM_INR = UUID.fromString("00000000-0000-0000-0000-000000000001");
	static final int ACCOUNTS = 6;
	static final long OPENING_BALANCE = 100_000;
	static final int THREADS = 16;
	static final int PER_THREAD = 500;
	static final int MAX_AMOUNT = 20_000;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	LedgerRepository ledger;

	@Autowired
	PlatformTransactionManager txManager;

	@Autowired
	AccountService accountService;

	@Autowired
	TransferService transferService;

	record Result(String variant, int committed, int insufficient, int backstop, int deadlocks, int gaveUp,
			int retries, Map<String, Integer> otherErrors, int mismatchedAccounts, long drift, int negativeAccounts,
			double seconds) {

		double throughput() {
			return (committed + insufficient + backstop + deadlocks + gaveUp
					+ otherErrors.values().stream().mapToInt(Integer::intValue).sum()) / seconds;
		}

	}

	@Test
	void anomaliesAppearOnlyWithoutProperLockingOrIsolation() throws Exception {
		var strategies = new TransferStrategies(jdbc, ledger, txManager);
		TransferStrategies.Strategy production = (from, to, amount) -> {
			transferService.transfer(new TransferCommand(UUID.randomUUID().toString(), from, to, amount, Currency.INR, null));
			return 0;
		};

		Map<String, Result> results = new LinkedHashMap<>();
		results.put("A", run("A. Naive read-modify-write (READ COMMITTED)", strategies.naiveReadModifyWrite()));
		results.put("A2", run("A2. Atomic increment, unlocked check (READ COMMITTED)", strategies.atomicIncrementUnlockedCheck()));
		results.put("B", run("B. FOR UPDATE in lock order (READ COMMITTED) — production", production));
		results.put("C", run("C. SERIALIZABLE, no locks, retry on 40001", strategies.serializableWithRetry(10)));
		report(results.values());

		Result a = results.get("A");
		assertThat(a.drift())
			.as("variant A must exhibit lost updates; if not, the workload isn't contended enough to be a meaningful experiment")
			.isPositive();

		Result a2 = results.get("A2");
		assertThat(a2.drift()).as("A2: atomic increments cannot lose updates").isZero();
		assertThat(a2.negativeAccounts()).as("A2: CHECK backstop keeps balances >= 0").isZero();

		for (Result safe : List.of(results.get("B"), results.get("C"))) {
			assertThat(safe.drift()).as("%s: cached balance drift", safe.variant()).isZero();
			assertThat(safe.mismatchedAccounts()).as("%s: accounts with cached != log", safe.variant()).isZero();
			assertThat(safe.negativeAccounts()).as("%s: negative balances", safe.variant()).isZero();
			assertThat(safe.backstop()).as("%s: CHECK backstop hits (app check should catch every overdraft)", safe.variant()).isZero();
			assertThat(safe.otherErrors()).as("%s: unexpected errors by SQLSTATE", safe.variant()).isEmpty();
		}
		assertThat(results.get("B").deadlocks()).as("B: deadlocks with lock ordering").isZero();
	}

	private Result run(String variant, TransferStrategies.Strategy strategy) throws Exception {
		List<UUID> accounts = IntStream.range(0, ACCOUNTS).mapToObj(i -> fundedAccount()).toList();
		var committed = new AtomicInteger();
		var insufficient = new AtomicInteger();
		var backstop = new AtomicInteger();
		var deadlocks = new AtomicInteger();
		var gaveUp = new AtomicInteger();
		var retries = new AtomicInteger();
		var other = new ConcurrentHashMap<String, AtomicInteger>();
		var start = new CountDownLatch(1);

		long began;
		long elapsed;
		try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
			List<Future<?>> workers = new ArrayList<>();
			for (int t = 0; t < THREADS; t++) {
				int thread = t;
				workers.add(pool.submit(() -> {
					var random = new Random(thread);
					await(start);
					for (int i = 0; i < PER_THREAD; i++) {
						UUID from = accounts.get(random.nextInt(ACCOUNTS));
						UUID to;
						do {
							to = accounts.get(random.nextInt(ACCOUNTS));
						}
						while (to.equals(from));
						try {
							retries.addAndGet(strategy.transfer(from, to, 1 + random.nextInt(MAX_AMOUNT)));
							committed.incrementAndGet();
						}
						catch (InsufficientFundsException e) {
							insufficient.incrementAndGet();
						}
						catch (RuntimeException e) {
							switch (String.valueOf(SqlStates.of(e))) {
								case "23514" -> backstop.incrementAndGet(); // CHECK balance >= 0
								case "40P01" -> deadlocks.incrementAndGet();
								case "40001" -> gaveUp.incrementAndGet(); // serialization failure after max retries
								default -> other.computeIfAbsent(SqlStates.of(e) != null ? SqlStates.of(e)
										: e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
							}
						}
					}
				}));
			}
			began = System.nanoTime();
			start.countDown();
			for (Future<?> w : workers) {
				w.get();
			}
			elapsed = System.nanoTime() - began;
		}

		String[] ids = accounts.stream().map(UUID::toString).toArray(String[]::new);
		var drift = jdbc.sql("""
				SELECT count(*) FILTER (WHERE b.balance <> COALESCE(e.total, 0)),
				       COALESCE(SUM(abs(b.balance - COALESCE(e.total, 0))), 0),
				       count(*) FILTER (WHERE b.balance < 0)
				  FROM account_balances b
				  LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM entries GROUP BY account_id) e
				         ON e.account_id = b.account_id
				 WHERE b.account_id::text = ANY (:ids)""")
			.param("ids", ids)
			.query((rs, n) -> new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) })
			.single();

		return new Result(variant, committed.get(), insufficient.get(), backstop.get(), deadlocks.get(), gaveUp.get(),
				retries.get(), new TreeMap<>(other.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
						en -> en.getValue().get()))), (int) drift[0], drift[1], (int) drift[2], elapsed / 1e9);
	}

	private UUID fundedAccount() {
		UUID id = accountService.createAccount("experiment", Currency.INR).id();
		transferService.transfer(new TransferCommand(UUID.randomUUID().toString(), SYSTEM_INR, id, OPENING_BALANCE,
				Currency.INR, "opening"));
		return id;
	}

	private static void report(Iterable<Result> results) {
		var md = new StringBuilder("""
				## Isolation-level experiment

				%,d transfer attempts per variant (%d threads × %d) over %d accounts opened with %,d each; amounts 1–%,d.

				| Variant | Committed | `insufficient_funds` | CHECK backstop hits | Deadlocks | SSI retries (gave up) | Other errors | Accounts with cached ≠ log | Total drift | Throughput (attempts/s) |
				|---|---|---|---|---|---|---|---|---|---|
				""".formatted(THREADS * PER_THREAD, THREADS, PER_THREAD, ACCOUNTS, OPENING_BALANCE, MAX_AMOUNT));
		for (Result r : results) {
			md.append("| %s | %,d | %,d | %,d | %,d | %,d (%d) | %s | %d | %,d | %,.0f |%n".formatted(r.variant(),
					r.committed(), r.insufficient(), r.backstop(), r.deadlocks(), r.retries(), r.gaveUp(),
					r.otherErrors().isEmpty() ? "0" : r.otherErrors().toString(), r.mismatchedAccounts(), r.drift(),
					r.throughput()));
		}
		md.append("%nOther errors are by SQLSTATE and asserted empty for B and C. Environment: %s.%n"
			.formatted(StressReport.environment()));
		StressReport.write("isolation-report.md", md.toString());
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
