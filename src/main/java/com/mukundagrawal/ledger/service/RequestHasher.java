package com.mukundagrawal.ledger.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.mukundagrawal.ledger.domain.TransferCommand;

/**
 * Canonical SHA-256 of a transfer request, used to tell "same request retried" from "key reused for a
 * different request" (docs/LLD.md §5).
 *
 * <p>Each field is encoded as {@code <utf8-byte-length>:<value>} in a fixed order, so no value (e.g. a
 * description containing {@code |} or {@code :}) can shift a boundary and make two different requests
 * collide. The idempotency key itself is not hashed — it is the lookup handle.
 */
public final class RequestHasher {

	private RequestHasher() {
	}

	public static byte[] hash(TransferCommand cmd) {
		MessageDigest sha256 = sha256();
		field(sha256, cmd.fromAccountId().toString());
		field(sha256, cmd.toAccountId().toString());
		field(sha256, Long.toString(cmd.amount()));
		field(sha256, cmd.currency().name());
		field(sha256, cmd.description() == null ? "" : cmd.description());
		return sha256.digest();
	}

	private static void field(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
		digest.update(bytes);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
		}
	}

}
