package com.mukundagrawal.ledger.api.dto;

import com.mukundagrawal.ledger.domain.Currency;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(@NotBlank @Size(max = 100) String name, @NotNull Currency currency) {
}
