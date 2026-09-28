package com.mukundagrawal.ledger.domain;

import java.util.List;

/** A page of statement lines, newest first. {@code nextCursor} is null on the last page. */
public record Statement(List<StatementLine> items, Long nextCursor) {
}
