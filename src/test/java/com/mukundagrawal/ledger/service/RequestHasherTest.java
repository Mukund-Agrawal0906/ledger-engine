package com.mukundagrawal.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.TransferCommand;

class RequestHasherTest {

	static final UUID A = UUID.fromString("3f6c1a52-8a0e-4d7e-9a57-6f7d0f3b2c11");
	static final UUID B = UUID.fromString("b7d2e0c4-1f3a-4c5e-8d9b-0a1b2c3d4e5f");

	static TransferCommand cmd(String key, UUID from, UUID to, long amount, Currency cur, String desc) {
		return new TransferCommand(key, from, to, amount, cur, desc);
	}

	@Test
	void isDeterministicAndIgnoresTheKey() {
		assertThat(RequestHasher.hash(cmd("k1", A, B, 100, Currency.INR, "rent")))
			.isEqualTo(RequestHasher.hash(cmd("k2", A, B, 100, Currency.INR, "rent")))
			.hasSize(32);
	}

	@Test
	void everyFieldChangesTheHash() {
		byte[] base = RequestHasher.hash(cmd("k", A, B, 100, Currency.INR, "rent"));

		assertThat(RequestHasher.hash(cmd("k", B, A, 100, Currency.INR, "rent"))).isNotEqualTo(base);
		assertThat(RequestHasher.hash(cmd("k", A, B, 101, Currency.INR, "rent"))).isNotEqualTo(base);
		assertThat(RequestHasher.hash(cmd("k", A, B, 100, Currency.USD, "rent"))).isNotEqualTo(base);
		assertThat(RequestHasher.hash(cmd("k", A, B, 100, Currency.INR, "Rent"))).isNotEqualTo(base);
	}

	@Test
	void nullAndEmptyDescriptionAreTheSameRequest() {
		assertThat(RequestHasher.hash(cmd("k", A, B, 100, Currency.INR, null)))
			.isEqualTo(RequestHasher.hash(cmd("k", A, B, 100, Currency.INR, "")));
	}

	@Test
	void separatorCharactersInDescriptionCannotForgeACollision() {
		// With naive "a|b|c" joining, moving a boundary character between fields could collide.
		// Length-prefixing makes the encoding injective.
		byte[] h1 = RequestHasher.hash(cmd("k", A, B, 100, Currency.INR, "1:x"));
		byte[] h2 = RequestHasher.hash(cmd("k", A, B, 100, Currency.INR, "1:x|"));
		byte[] h3 = RequestHasher.hash(cmd("k", A, B, 100, Currency.INR, "|1:x"));
		assertThat(h1).isNotEqualTo(h2).isNotEqualTo(h3);
		assertThat(h2).isNotEqualTo(h3);
	}

}
