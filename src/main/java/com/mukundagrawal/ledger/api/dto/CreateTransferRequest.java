package com.mukundagrawal.ledger.api.dto;

import java.util.UUID;

import com.mukundagrawal.ledger.domain.Currency;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Amount is minor units; the 1e15 cap keeps values exact for JavaScript clients (docs/DATA_MODELS.md §1). */
public record CreateTransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId,
		@NotNull @Min(1) @Max(1_000_000_000_000_000L) Long amount, @NotNull Currency currency,
		@Size(max = 255) String description) {
}
