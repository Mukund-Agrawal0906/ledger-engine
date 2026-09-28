package com.mukundagrawal.ledger.error;

import java.util.UUID;

import org.springframework.http.HttpStatus;

public final class TransferNotFoundException extends LedgerException {

	public TransferNotFoundException(UUID transferId) {
		super("transfer_not_found", HttpStatus.NOT_FOUND,
				"Transfer " + transferId + " does not exist.");
	}

}
