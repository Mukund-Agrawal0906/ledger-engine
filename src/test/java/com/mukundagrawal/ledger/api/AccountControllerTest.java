package com.mukundagrawal.ledger.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mukundagrawal.ledger.domain.Account;
import com.mukundagrawal.ledger.domain.AccountKind;
import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.Statement;
import com.mukundagrawal.ledger.domain.StatementLine;
import com.mukundagrawal.ledger.error.AccountNotFoundException;
import com.mukundagrawal.ledger.service.AccountService;

@WebMvcTest(AccountController.class)
@Import(JsonStrictnessConfig.class)
class AccountControllerTest {

	static final UUID ID = UUID.fromString("3f6c1a52-8a0e-4d7e-9a57-6f7d0f3b2c11");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AccountService accounts;

	@Test
	void createReturns201WithLocation() throws Exception {
		given(accounts.createAccount(eq("Alice"), eq(Currency.INR)))
			.willReturn(new Account(ID, "Alice", AccountKind.USER, Currency.INR, 0, 0, Instant.parse("2026-09-28T10:15:30Z")));

		mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "  Alice ", "currency": "INR"}"""))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/accounts/" + ID))
			.andExpect(jsonPath("$.id").value(ID.toString()))
			.andExpect(jsonPath("$.kind").value("USER"))
			.andExpect(jsonPath("$.balance").value(0))
			.andExpect(jsonPath("$.createdAt").value("2026-09-28T10:15:30Z"));
	}

	@Test
	void blankNameIsAFieldError() throws Exception {
		mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "  ", "currency": "INR"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("validation_failed"))
			.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void unsupportedCurrencyIsRejected() throws Exception {
		mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Alice", "currency": "EUR"}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("validation_failed"));
	}

	@Test
	void unknownFieldIsRejected() throws Exception {
		mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Alice", "currency": "INR", "balance": 1000000}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("validation_failed"));
	}

	@Test
	void unknownAccountIs404() throws Exception {
		given(accounts.getAccount(any())).willThrow(new AccountNotFoundException(ID));

		mvc.perform(get("/accounts/" + ID))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("account_not_found"))
			.andExpect(jsonPath("$.type").value("https://ledger-engine.dev/problems/account-not-found"));
	}

	@Test
	void statementReturnsItemsAndCursor() throws Exception {
		var line = new StatementLine(41, UUID.randomUUID(), -300, Instant.parse("2026-09-28T10:16:02Z"), "rent");
		given(accounts.getStatement(ID, 42L, 1)).willReturn(new Statement(List.of(line), 41L));

		mvc.perform(get("/accounts/" + ID + "/entries?limit=1&before=42"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].entryId").value(41))
			.andExpect(jsonPath("$.items[0].amount").value(-300))
			.andExpect(jsonPath("$.items[0].description").value("rent"))
			.andExpect(jsonPath("$.nextCursor").value(41));
	}

	@Test
	void statementLimitIsBounded() throws Exception {
		for (String query : new String[] { "limit=0", "limit=201", "before=0", "limit=abc" }) {
			mvc.perform(get("/accounts/" + ID + "/entries?" + query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("validation_failed"));
		}
	}

	@Test
	void malformedIdIs400() throws Exception {
		mvc.perform(get("/accounts/not-a-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("validation_failed"));
	}

}
