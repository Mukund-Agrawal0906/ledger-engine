package com.mukundagrawal.ledger.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mukundagrawal.ledger.api.dto.AccountResponse;
import com.mukundagrawal.ledger.api.dto.CreateAccountRequest;
import com.mukundagrawal.ledger.domain.Account;
import com.mukundagrawal.ledger.service.AccountService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
public class AccountController {

	private final AccountService accounts;

	public AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@PostMapping
	public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
		Account account = accounts.createAccount(request.name().strip(), request.currency());
		return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(AccountResponse.from(account));
	}

	@GetMapping("/{id}")
	public AccountResponse get(@PathVariable UUID id) {
		return AccountResponse.from(accounts.getAccount(id));
	}

}
