package com.mukundagrawal.ledger.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.mukundagrawal.ledger.domain.Currency;
import com.mukundagrawal.ledger.domain.Entry;
import com.mukundagrawal.ledger.domain.Transfer;
import com.mukundagrawal.ledger.error.InsufficientFundsException;
import com.mukundagrawal.ledger.service.TransferResult;
import com.mukundagrawal.ledger.service.TransferService;

@WebMvcTest(TransferController.class)
@Import(JsonStrictnessConfig.class)
class TransferControllerTest {

	static final UUID FROM = UUID.fromString("00000000-0000-0000-0000-000000000001");
	static final UUID TO = UUID.fromString("3f6c1a52-8a0e-4d7e-9a57-6f7d0f3b2c11");
	static final UUID TX = UUID.fromString("b7d2e0c4-1f3a-4c5e-8d9b-0a1b2c3d4e5f");
	static final Transfer TRANSFER = new Transfer(TX, FROM, TO, 500_000, Currency.INR, "deposit",
			Instant.parse("2026-09-28T10:16:02Z"), List.of(new Entry(1, TX, FROM, -500_000), new Entry(2, TX, TO, 500_000)));
	static final String BODY = """
			{"fromAccountId": "%s", "toAccountId": "%s", "amount": 500000, "currency": "INR", "description": "deposit"}"""
		.formatted(FROM, TO);

	@Autowired
	MockMvc mvc;

	@MockitoBean
	TransferService transfers;

	static MockHttpServletRequestBuilder postTransfer(String body) {
		return post("/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "key-1").content(body);
	}

	@Test
	void firstExecutionIs201() throws Exception {
		given(transfers.transfer(any())).willReturn(new TransferResult(TRANSFER, false));

		mvc.perform(postTransfer(BODY))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/transfers/" + TX))
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andExpect(jsonPath("$.id").value(TX.toString()))
			.andExpect(jsonPath("$.amount").value(500_000))
			.andExpect(jsonPath("$.entries[0].amount").value(-500_000))
			.andExpect(jsonPath("$.entries[1].accountId").value(TO.toString()));
	}

	@Test
	void replayIs200WithHeader() throws Exception {
		given(transfers.transfer(any())).willReturn(new TransferResult(TRANSFER, true));

		mvc.perform(postTransfer(BODY))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.id").value(TX.toString()));
	}

	@Test
	void missingOrInvalidIdempotencyKeyIsRejected() throws Exception {
		for (String key : new String[] { null, "   ", "x".repeat(256), "clé" }) {
			var request = post("/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY);
			if (key != null) {
				request.header("Idempotency-Key", key);
			}
			mvc.perform(request)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("missing_idempotency_key"));
		}
		verify(transfers, never()).transfer(any());
	}

	@Test
	void ambiguousAmountsAreRejected() throws Exception {
		for (String amount : new String[] { "100.5", "\"100\"", "0", "-5", "1000000000000001", "null" }) {
			mvc.perform(postTransfer(BODY.replace("500000", amount)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("validation_failed"));
		}
		verify(transfers, never()).transfer(any());
	}

	@Test
	void unknownFieldIsRejected() throws Exception {
		mvc.perform(postTransfer(BODY.replace("\"amount\"", "\"ammount\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("validation_failed"));
	}

	@Test
	void businessRuleViolationsAre422() throws Exception {
		given(transfers.transfer(any())).willThrow(new InsufficientFundsException(FROM, 10, 500_000));

		mvc.perform(postTransfer(BODY))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("insufficient_funds"))
			.andExpect(jsonPath("$.status").value(422));
	}

	@Test
	void lockTimeoutIs503WithRetryAfter() throws Exception {
		given(transfers.transfer(any()))
			.willThrow(new CannotAcquireLockException("lock", new SQLException("canceling statement due to lock timeout", "55P03")));

		mvc.perform(postTransfer(BODY))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "1"))
			.andExpect(jsonPath("$.code").value("service_busy"));
	}

	@Test
	void unexpectedFailureIs500() throws Exception {
		given(transfers.transfer(any())).willThrow(new IllegalStateException("boom"));

		mvc.perform(postTransfer(BODY))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("internal_error"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("boom"))));
	}

}
