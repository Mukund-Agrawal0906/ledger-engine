package com.mukundagrawal.ledger.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The single global order in which balance rows are locked (docs/LLD.md §4). Acquiring locks in one
 * total order makes a wait-for cycle — and so a deadlock — impossible.
 *
 * <p>Compares {@code UUID.toString()} lexicographically, which matches Postgres's {@code uuid} ordering
 * (unsigned bytes). {@link UUID#compareTo} is deliberately NOT used: it compares the halves as signed
 * longs and disagrees with Postgres whenever the high bit is set, which would deadlock against any SQL
 * path that locks with {@code ORDER BY account_id FOR UPDATE}.
 */
public final class LockOrder {

	public static final Comparator<UUID> ACCOUNT_ID = Comparator.comparing(UUID::toString);

	private LockOrder() {
	}

	public static List<UUID> sorted(UUID a, UUID b) {
		return ACCOUNT_ID.compare(a, b) <= 0 ? List.of(a, b) : List.of(b, a);
	}

}
