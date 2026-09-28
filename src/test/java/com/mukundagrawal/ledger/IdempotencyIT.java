package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.error.IdempotencyKeyReusedException;
import com.mukundagrawal.ledger.error.InsufficientFundsException;
import com.mukundagrawal.ledger.service.TransferResult;

/** Invariant I5: exactly-once transfers per Idempotency-Key. */
class IdempotencyIT extends LedgerITSupport {

	@Test
	void sequentialRetryReplaysTheOriginal() {
		UUID alice = newFundedAccount(Currency.INR, 1_000);
		UUID bob = newAccount(Currency.INR);
		var cmd = command("seq-" + UUID.randomUUID(), alice, bob, 100, Currency.INR);

		TransferResult first = transferService.transfer(cmd);
		TransferResult retry = transferService.transfer(cmd);

		assertThat(first.replayed()).isFalse();
		assertThat(retry.replayed()).isTrue();
		assertThat(retry.transfer()).isEqualTo(first.transfer());
		assertThat(balance(alice)).isEqualTo(900);
	}

	@Test
	void concurrentRetriesCommitOnce() {
		int threads = 50;
		UUID alice = newFundedAccount(Currency.INR, 1_000);
		UUID bob = newAccount(Currency.INR);
		String key = "race-" + UUID.randomUUID();
		var start = new CountDownLatch(1);

		List<TransferResult> results;
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<CompletableFuture<TransferResult>> calls = java.util.stream.IntStream.range(0, threads)
				.mapToObj(i -> CompletableFuture.supplyAsync(() -> {
					TransferServiceIT.await(start);
					return transferService.transfer(command(key, alice, bob, 100, Currency.INR));
				}, pool))
				.toList();
			start.countDown();
			results = calls.stream().map(CompletableFuture::join).toList();
		}

		assertThat(results).extracting(r -> r.transfer().id()).containsOnly(results.getFirst().transfer().id());
		assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
		assertThat(balance(alice)).as("debited exactly once").isEqualTo(900);
		assertThat(balance(bob)).isEqualTo(100);
		long committedForKey = jdbc.sql("""
				SELECT count(*) FROM transactions t JOIN idempotency_keys k ON k.transaction_id = t.id
				WHERE k.key = ?""").param(key).query(Long.class).single();
		assertThat(committedForKey).isOne();
		invariants().assertAllInvariants();
	}

	@Test
	void keyReuseWithDifferentBodyIsRejected() {
		UUID alice = newFundedAccount(Currency.INR, 1_000);
		UUID bob = newAccount(Currency.INR);
		String key = "reuse-" + UUID.randomUUID();
		transferService.transfer(command(key, alice, bob, 100, Currency.INR));

		assertThatThrownBy(() -> transferService.transfer(command(key, alice, bob, 200, Currency.INR)))
			.isInstanceOf(IdempotencyKeyReusedException.class);

		assertThat(balance(alice)).isEqualTo(900);
		assertThat(balance(bob)).isEqualTo(100);
	}

	@Test
	void failedTransferDoesNotConsumeKey() {
		UUID alice = newAccount(Currency.INR);
		UUID bob = newAccount(Currency.INR);
		var cmd = command("retry-after-funding-" + UUID.randomUUID(), alice, bob, 100, Currency.INR);

		assertThatThrownBy(() -> transferService.transfer(cmd)).isInstanceOf(InsufficientFundsException.class);
		assertThat(keyExists(cmd.idempotencyKey())).isFalse();

		transfer(SYSTEM_INR, alice, 100, Currency.INR);
		TransferResult retried = transferService.transfer(cmd);

		assertThat(retried.replayed()).as("executed fresh, not replayed").isFalse();
		assertThat(balance(bob)).isEqualTo(100);
	}

	@Test
	void replayReturnsTheOriginalEvenIfItWouldFailNow() {
		UUID alice = newFundedAccount(Currency.INR, 100);
		UUID bob = newAccount(Currency.INR);
		var cmd = command("drain-" + UUID.randomUUID(), alice, bob, 100, Currency.INR);

		TransferResult first = transferService.transfer(cmd); // drains alice to 0
		TransferResult retry = transferService.transfer(cmd); // would be insufficient_funds if re-executed

		assertThat(retry.replayed()).isTrue();
		assertThat(retry.transfer()).isEqualTo(first.transfer());
		assertThat(balance(alice)).isZero();
	}

}
