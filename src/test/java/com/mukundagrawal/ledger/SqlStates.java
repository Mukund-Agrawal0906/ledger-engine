package com.mukundagrawal.ledger;

import java.sql.SQLException;

/** Test helper: digs the Postgres SQLSTATE and message out of Spring's exception wrappers. */
final class SqlStates {

	private SqlStates() {
	}

	static String of(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
		}
		return null;
	}

}
