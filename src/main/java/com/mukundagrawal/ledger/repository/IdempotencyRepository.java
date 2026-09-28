package com.mukundagrawal.ledger.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Exactly-once claims on {@code Idempotency-Key}s (docs/LLD.md §5, invariant I5). */
@Repository
public class IdempotencyRepository {

	public record IdempotencyRecord(byte[] requestHash, UUID transactionId) {
	}

	private final JdbcClient jdbc;

	public IdempotencyRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Claims {@code key} for {@code transactionId}. Returns {@code false} if the key is already committed.
	 *
	 * <p>If another in-flight transaction holds an uncommitted claim on the same key, this call BLOCKS on
	 * the primary-key index until that transaction ends: it then returns {@code false} if the other
	 * committed, or claims the key itself if the other rolled back. That wait is the whole deduplication
	 * mechanism — there is no "in progress" state to get stuck.
	 */
	public boolean tryClaim(String key, byte[] requestHash, UUID transactionId) {
		return jdbc.sql("""
				INSERT INTO idempotency_keys (key, request_hash, transaction_id)
				VALUES (:key, :hash, :tx)
				ON CONFLICT (key) DO NOTHING""")
			.param("key", key)
			.param("hash", requestHash)
			.param("tx", transactionId)
			.update() == 1;
	}

	public Optional<IdempotencyRecord> find(String key) {
		return jdbc.sql("SELECT request_hash, transaction_id FROM idempotency_keys WHERE key = :key")
			.param("key", key)
			.query((rs, n) -> new IdempotencyRecord(rs.getBytes("request_hash"), rs.getObject("transaction_id", UUID.class)))
			.optional();
	}

}
