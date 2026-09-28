package com.mukundagrawal.ledger.domain;

/** USER accounts can never go negative; SYSTEM accounts represent money outside the ledger and can. */
public enum AccountKind {
	USER, SYSTEM
}
