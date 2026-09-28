package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.Entry;
import com.mukundagrawal.ledger.domain.Transfer;
import com.mukundagrawal.ledger.error.AccountNotFoundException;
import com.mukundagrawal.ledger.error.CurrencyMismatchException;
import com.mukundagrawal.ledger.error.InsufficientFundsException;
import com.mukundagrawal.ledger.error.SameAccountException;
import com.mukundagrawal.ledger.error.TransferNotFoundException;

class TransferServiceIT extends LedgerITSupport {

	@Test
	void fundingMovesMoneyOutOfTheSystemAccount() {
		UUID alice = newAccount(Currency.INR);
		long systemBefore = balance(SYSTEM_INR);

		var result = transfer(SYSTEM_INR, alice, 500_000, Currency.INR);

		assertThat(result.replayed()).isFalse();
		assertThat(balance(alice)).isEqualTo(500_000);
		assertThat(balance(SYSTEM_INR)).isEqualTo(systemBefore - 500_000);
	}

	@Test
	void userToUserTransferWritesBalancedEntries() {
		UUID alice = newFundedAccount(Currency.INR, 1_000);
		UUID bob = newAccount(Currency.INR);

		Transfer t = transfer(alice, bob, 300, Currency.INR).transfer();

		assertThat(balance(alice)).isEqualTo(700);
		assertThat(balance(bob)).isEqualTo(300);
		assertThat(t.fromAccountId()).isEqualTo(alice);
		assertThat(t.toAccountId()).isEqualTo(bob);
		assertThat(t.amount()).isEqualTo(300);
		assertThat(t.currency()).isEqualTo(Currency.INR);
		assertThat(t.entries()).extracting(Entry::accountId, Entry::amount)
			.containsExactly(tuple(alice, -300L),
					tuple(bob, 300L));
		assertThat(transferService.getTransfer(t.id())).isEqualTo(t);
	}

	@Test
	void unknownTransferIsNotFound() {
		assertThatThrownBy(() -> transferService.getTransfer(UUID.randomUUID()))
			.isInstanceOf(TransferNotFoundException.class);
	}

	@Test
	void overdraftIsRejected() {
		UUID alice = newFundedAccount(Currency.INR, 100);
		UUID bob = newAccount(Currency.INR);

		assertRejectedWithoutSideEffects(alice, bob, 101, Currency.INR, InsufficientFundsException.class);
		transfer(alice, bob, 100, Currency.INR); // exactly the balance is fine
		assertThat(balance(alice)).isZero();
	}

	@Test
	void sameAccountIsRejected() {
		UUID alice = newFundedAccount(Currency.INR, 100);
		assertRejectedWithoutSideEffects(alice, alice, 10, Currency.INR, SameAccountException.class);
	}

	@Test
	void currencyMismatchIsRejected() {
		UUID inr = newFundedAccount(Currency.INR, 100);
		UUID usd = newAccount(Currency.USD);

		assertRejectedWithoutSideEffects(inr, usd, 10, Currency.INR, CurrencyMismatchException.class);
		assertRejectedWithoutSideEffects(inr, usd, 10, Currency.USD, CurrencyMismatchException.class);
	}

	@Test
	void unknownAccountIsRejected() {
		UUID alice = newFundedAccount(Currency.INR, 100);
		UUID ghost = UUID.randomUUID();

		assertRejectedWithoutSideEffects(alice, ghost, 10, Currency.INR, AccountNotFoundException.class);
		assertRejectedWithoutSideEffects(ghost, alice, 10, Currency.INR, AccountNotFoundException.class);
	}

	/**
	 * I7(b)-lite: A->B and B->A concurrently. Without a global lock order each side would grab its
	 * source row first and wait on the other — a textbook deadlock (40P01). The full 51k-transfer version
	 * is the Phase 3 stress test.
	 *
	 * <p>8 threads, not 2: with only two, the threads fall into a lock-step convoy where the loser always
	 * waits on its FIRST lock while holding nothing, so no cycle forms and a broken lock order goes
	 * undetected. (Verified by mutation: with 2 threads a source-first lock order passed this test.)
	 */
	@Test
	void opposingTransfersDoNotDeadlock() throws Exception {
		int threads = 8;
		int iterations = 250;
		UUID a = newFundedAccount(Currency.INR, 1_000_000);
		UUID b = newFundedAccount(Currency.INR, 1_000_000);
		var errors = new ConcurrentLinkedQueue<Throwable>();
		var start = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<CompletableFuture<Void>> runs = java.util.stream.IntStream.range(0, threads).mapToObj(n -> CompletableFuture.runAsync(() -> {
				UUID from = n % 2 == 0 ? a : b;
				UUID to = from.equals(a) ? b : a;
				await(start);
				for (int i = 0; i < iterations; i++) {
					try {
						transfer(from, to, 1, Currency.INR);
					}
					catch (Throwable t) {
						errors.add(t);
					}
				}
			}, pool)).toList();
			start.countDown();
			CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).join();
		}

		assertThat(errors).as("errors (a deadlock would show up here as SQLSTATE 40P01)").isEmpty();
		// Equal flow both ways: any lost update would show as a drift here (I7a).
		assertThat(balance(a)).isEqualTo(1_000_000);
		assertThat(balance(b)).isEqualTo(1_000_000);
		invariants().assertAllInvariants();
	}

	private void assertRejectedWithoutSideEffects(UUID from, UUID to, long amount, Currency currency,
			Class<? extends Throwable> expected) {
		Long fromBefore = balanceOrNull(from);
		Long toBefore = balanceOrNull(to);
		String key = UUID.randomUUID().toString();

		assertThatThrownBy(() -> transferService.transfer(command(key, from, to, amount, currency)))
			.isInstanceOf(expected);

		assertThat(balanceOrNull(from)).isEqualTo(fromBefore);
		assertThat(balanceOrNull(to)).isEqualTo(toBefore);
		assertThat(keyExists(key)).as("a rejected transfer must not consume its idempotency key").isFalse();
	}

	private Long balanceOrNull(UUID id) {
		try {
			return balance(id);
		}
		catch (AccountNotFoundException e) {
			return null;
		}
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

}
