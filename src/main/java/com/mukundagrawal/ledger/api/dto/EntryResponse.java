package com.mukundagrawal.ledger.api.dto;

import java.util.UUID;

public record EntryResponse(UUID accountId, long amount) {
}
