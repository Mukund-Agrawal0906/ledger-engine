package com.mukundagrawal.ledger.service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.mukundagrawal.ledger.domain.Transfer;
import com.mukundagrawal.ledger.domain.TransferCommand;
import com.mukundagrawal.ledger.error.AccountNotFoundException;
import com.mukundagrawal.ledger.error.CurrencyMismatchException;
import com.mukundagrawal.ledger.error.IdempotencyKeyReusedException;
import com.mukundagrawal.ledger.error.InsufficientFundsException;
import com.mukundagrawal.ledger.error.SameAccountException;
import com.mukundagrawal.ledger.error.TransferNotFoundException;
import com.mukundagrawal.ledger.repository.BalanceRepository;
import com.mukundagrawal.ledger.repository.BalanceRepository.LockedBalance;
import com.mukundagrawal.ledger.repository.IdempotencyRepository;
import com.mukundagrawal.ledger.repository.LedgerRepository;

/**
 * Moves money. The algorithm is docs/LLD.md §3, step for step; step numbers are referenced below.
 *
 * <p>Everything runs in ONE database transaction, so any exception rolls back the key claim, the log
 * writes and the balance updates together. A failed transfer therefore never consumes its key.
 */
@Service
public class TransferService {

	private final IdempotencyRepository idempotency;

	private final BalanceRepository balances;

	private final LedgerRepository ledger;

	public TransferService(IdempotencyRepository idempotency, BalanceRepository balances, LedgerRepository ledger) {
		this.idempotency = idempotency;
		this.balances = balances;
		this.ledger = ledger;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TransferResult transfer(TransferCommand cmd) {
		UUID from = cmd.fromAccountId();
		UUID to = cmd.toAccountId();
		long amount = cmd.amount();

		// P1: stateless rule, no DB needed.
		if (from.equals(to)) {
			throw new SameAccountException(from);
		}

		// 1. Ids and hash are computed up front, so nothing slow happens while row locks are held.
		UUID txId = UUID.randomUUID();
		byte[] requestHash = RequestHasher.hash(cmd);

		// 2. Claim the key FIRST: global lock order is key -> balances (ascending id). May block on a concurrent duplicate.
		if (!idempotency.tryClaim(cmd.idempotencyKey(), requestHash, txId)) {
			return replay(cmd.idempotencyKey(), requestHash);
		}

		// 3. Lock both balance rows in the one global order (deadlock avoidance, LLD §4).
		List<UUID> lockOrder = LockOrder.sorted(from, to);
		Map<UUID, LockedBalance> locked = new HashMap<>();
		for (UUID id : lockOrder) {
			locked.put(id, balances.lockForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id)));
		}
		LockedBalance source = locked.get(from);
		LockedBalance destination = locked.get(to);

		// 4. Currency must match on both sides; there is no FX.
		if (source.currency() != cmd.currency() || destination.currency() != cmd.currency()) {
			throw new CurrencyMismatchException(cmd.currency(), source.currency(), destination.currency());
		}

		// 5. Funds check against the LOCKED balance — nobody can change it until we commit (I4).
		if (!source.allowNegative() && source.balance() < amount) {
			throw new InsufficientFundsException(from, source.balance(), amount);
		}

		// 6. Append to the log.
		ledger.insertTransaction(txId, cmd.description());
		ledger.insertTransferEntries(txId, from, to, amount);

		// 7. Update the balance cache in the same transaction (I3), in lock order.
		for (UUID id : lockOrder) {
			balances.applyDelta(id, id.equals(from) ? -amount : amount);
		}

		// 8. Read back. 9. COMMIT happens when this method returns; deferred I1 checks run then.
		return new TransferResult(ledger.findTransfer(txId).orElseThrow(), false);
	}

	@Transactional(readOnly = true)
	public Transfer getTransfer(UUID id) {
		return ledger.findTransfer(id).orElseThrow(() -> new TransferNotFoundException(id));
	}

	/** R: the key is already committed. Same payload -> the original transfer; different payload -> 422. */
	private TransferResult replay(String key, byte[] requestHash) {
		var existing = idempotency.find(key)
			.orElseThrow(() -> new IllegalStateException("Key " + key + " conflicted but is not visible"));
		if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
			throw new IdempotencyKeyReusedException(key);
		}
		Transfer original = ledger.findTransfer(existing.transactionId())
			.orElseThrow(() -> new IllegalStateException("Key " + key + " points at a missing transaction"));
		return new TransferResult(original, true);
	}

}
