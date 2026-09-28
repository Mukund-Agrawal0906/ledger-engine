package com.mukundagrawal.ledger.stress;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mukundagrawal.ledger.LedgerITSupport;
import com.mukundagrawal.ledger.domain.Currency;

/**
 * The same kind of contended workload as {@link ConcurrencyStressIT}, but through the real HTTP stack
 * (embedded Tomcat, JSON, validation, error mapping). Separates web-layer cost from DB cost.
 */
@Tag("stress")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpStressIT extends LedgerITSupport {

	static final int USER_ACCOUNTS = 10;
	static final int THREADS = 16;
	static final int PER_THREAD = 320;
	static final long OPENING_BALANCE = 1_000_000;
	static final int MAX_AMOUNT = 50_000;

	record Committed(UUID from, UUID to, long amount) {
	}

	@LocalServerPort
	int port;

	@Test
	void httpLoadPreservesInvariants() throws Exception {
		List<UUID> accounts = IntStream.range(0, USER_ACCOUNTS)
			.mapToObj(i -> newFundedAccount(Currency.INR, OPENING_BALANCE))
			.toList();
		HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
		URI endpoint = URI.create("http://localhost:" + port + "/transfers");

		var committed = new ConcurrentLinkedQueue<Committed>();
		var insufficient = new AtomicInteger();
		var unexpected = new ConcurrentHashMap<String, AtomicInteger>();
		long[] latencies = new long[THREADS * PER_THREAD];
		var start = new CountDownLatch(1);

		long elapsed;
		try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
			List<Future<?>> workers = new ArrayList<>();
			for (int t = 0; t < THREADS; t++) {
				int thread = t;
				workers.add(pool.submit(() -> {
					var random = new Random(1_000 + thread);
					ConcurrencyStressIT.await(start);
					for (int i = 0; i < PER_THREAD; i++) {
						UUID from = accounts.get(random.nextInt(USER_ACCOUNTS));
						UUID to;
						do {
							to = accounts.get(random.nextInt(USER_ACCOUNTS));
						}
						while (to.equals(from));
						long amount = 1 + random.nextInt(MAX_AMOUNT);
						HttpRequest request = HttpRequest.newBuilder(endpoint)
							.header("Content-Type", "application/json")
							.header("Idempotency-Key", UUID.randomUUID().toString())
							.POST(HttpRequest.BodyPublishers.ofString("""
									{"fromAccountId":"%s","toAccountId":"%s","amount":%d,"currency":"INR"}"""
								.formatted(from, to, amount)))
							.build();
						long t0 = System.nanoTime();
						try {
							HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
							latencies[thread * PER_THREAD + i] = System.nanoTime() - t0;
							if (response.statusCode() == 201) {
								committed.add(new Committed(from, to, amount));
							}
							else if (response.statusCode() == 422 && response.body().contains("\"insufficient_funds\"")) {
								insufficient.incrementAndGet();
							}
							else {
								unexpected.computeIfAbsent("HTTP " + response.statusCode(), k -> new AtomicInteger()).incrementAndGet();
							}
						}
						catch (Exception e) {
							latencies[thread * PER_THREAD + i] = System.nanoTime() - t0;
							unexpected.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
						}
					}
					return null;
				}));
			}
			long began = System.nanoTime();
			start.countDown();
			for (Future<?> w : workers) {
				w.get();
			}
			elapsed = System.nanoTime() - began;
		}

		int total = THREADS * PER_THREAD;
		double seconds = elapsed / 1e9;
		StressReport.write("http-stress-report.md", """
				## HTTP load test

				| Metric | Value |
				|---|---|
				| Requests | %,d (%d client threads × %d) over %d hot accounts, real HTTP to embedded Tomcat |
				| Committed (201) | %,d |
				| Rejected `insufficient_funds` (422) | %,d |
				| Unexpected responses | %s |
				| Throughput | %,.0f requests/s |
				| Latency p50 / p95 / p99 / max | %.1f / %.1f / %.1f / %.1f ms |

				Environment: %s.
				""".formatted(total, THREADS, PER_THREAD, USER_ACCOUNTS, committed.size(), insufficient.get(),
				unexpected.isEmpty() ? "0" : unexpected.toString(), total / seconds, StressReport.percentileMs(latencies, 50),
				StressReport.percentileMs(latencies, 95), StressReport.percentileMs(latencies, 99),
				StressReport.percentileMs(latencies, 100), StressReport.environment()));

		assertThat(unexpected).as("unexpected HTTP statuses / client errors").isEmpty();
		assertThat(insufficient.get()).isPositive();

		// I7(a) from the client's point of view: 201 responses alone must explain every final balance.
		Map<UUID, Long> expected = new HashMap<>();
		accounts.forEach(a -> expected.put(a, OPENING_BALANCE));
		for (Committed c : committed) {
			expected.merge(c.from(), -c.amount(), Long::sum);
			expected.merge(c.to(), c.amount(), Long::sum);
		}
		for (UUID a : accounts) {
			assertThat(balance(a)).as("I7a: balance of %s", a).isEqualTo(expected.get(a));
		}
		invariants().assertAllInvariants();
	}

}
