package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.Statement;
import com.mukundagrawal.ledger.domain.StatementLine;
import com.mukundagrawal.ledger.error.AccountNotFoundException;

class StatementIT extends LedgerITSupport {

	@Test
	void keysetPagesCoverEveryEntryNewestFirstWithoutGapsOrDuplicates() {
		UUID alice = newFundedAccount(Currency.INR, 1_000); // entry 1: +1000
		UUID bob = newAccount(Currency.INR);
		for (int i = 1; i <= 6; i++) {
			transfer(alice, bob, i, Currency.INR); // entries 2..7: -1 .. -6
		}

		List<StatementLine> seen = new ArrayList<>();
		List<Integer> pageSizes = new ArrayList<>();
		Long cursor = null;
		do {
			Statement page = accountService.getStatement(alice, cursor, 3);
			pageSizes.add(page.items().size());
			seen.addAll(page.items());
			cursor = page.nextCursor();
		}
		while (cursor != null);

		assertThat(pageSizes).containsExactly(3, 3, 1);
		assertThat(seen).extracting(StatementLine::amount).containsExactly(-6L, -5L, -4L, -3L, -2L, -1L, 1_000L);
		assertThat(seen).extracting(StatementLine::entryId).isSortedAccordingTo((a, b) -> Long.compare(b, a))
			.doesNotHaveDuplicates();
		assertThat(seen.stream().mapToLong(StatementLine::amount).sum()).as("statement sums to balance")
			.isEqualTo(balance(alice));
	}

	@Test
	void exactPageBoundaryHasNoEmptyTrailingPage() {
		UUID alice = newFundedAccount(Currency.INR, 1_000);
		transfer(alice, newAccount(Currency.INR), 1, Currency.INR);

		Statement page = accountService.getStatement(alice, null, 2);

		assertThat(page.items()).hasSize(2);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void accountWithoutEntriesHasEmptyStatement() {
		Statement page = accountService.getStatement(newAccount(Currency.USD), null, 50);
		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void unknownAccountIsNotFound() {
		assertThatThrownBy(() -> accountService.getStatement(UUID.randomUUID(), null, 10))
			.isInstanceOf(AccountNotFoundException.class);
	}

}
