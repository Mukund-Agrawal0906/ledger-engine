package com.mukundagrawal.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** The whole vertical slice over HTTP against real Postgres: accounts, funding, transfer, replay, reads. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransferApiIT {

	static final String SYSTEM_INR = "00000000-0000-0000-0000-000000000001";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void endToEnd() throws Exception {
		String alice = createAccount("Alice");
		String bob = createAccount("Bob");

		transfer(UUID.randomUUID().toString(), SYSTEM_INR, alice, 1_000).andExpect(status().isCreated());

		String key = UUID.randomUUID().toString();
		String first = transfer(key, alice, bob, 400)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entries.length()").value(2))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(first, "$.id");

		// Client timed out and retries: same key, same body -> the same transfer, not a second debit.
		String replay = transfer(key, alice, bob, 400)
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andReturn().getResponse().getContentAsString();
		assertThat(replay).isEqualTo(first);

		mvc.perform(get("/transfers/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(400));
		mvc.perform(get("/accounts/" + alice)).andExpect(jsonPath("$.balance").value(600)).andExpect(jsonPath("$.version").value(2));
		mvc.perform(get("/accounts/" + bob)).andExpect(jsonPath("$.balance").value(400));

		transfer(UUID.randomUUID().toString(), alice, bob, 601)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("insufficient_funds"));
		transfer(key, alice, bob, 1)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("idempotency_key_reused"));

		new LedgerAssertions(jdbc).assertAllInvariants();
	}

	private String createAccount(String name) throws Exception {
		String body = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"%s\", \"currency\": \"INR\"}".formatted(name)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions transfer(String key, String from, String to, long amount) throws Exception {
		return mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", key)
			.content("""
					{"fromAccountId": "%s", "toAccountId": "%s", "amount": %d, "currency": "INR"}"""
				.formatted(from, to, amount)));
	}

}
