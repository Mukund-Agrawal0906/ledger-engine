package com.mukundagrawal.ledger.api.dto;

import java.util.List;

import com.mukundagrawal.ledger.domain.Statement;
import com.mukundagrawal.ledger.domain.StatementLine;

public record StatementResponse(List<StatementLine> items, Long nextCursor) {

	public static StatementResponse from(Statement s) {
		return new StatementResponse(s.items(), s.nextCursor());
	}

}
