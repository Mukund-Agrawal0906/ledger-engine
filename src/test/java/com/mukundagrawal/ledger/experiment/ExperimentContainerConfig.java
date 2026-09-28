package com.mukundagrawal.ledger.experiment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A dedicated Postgres for the isolation experiment. Being a different configuration class from
 * {@code TestcontainersConfiguration}, it gets its own Spring context and container — required because
 * variant A deliberately corrupts cached balances, which must never reach other ITs' invariant checks.
 */
@TestConfiguration(proxyBeanMethods = false)
class ExperimentContainerConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer experimentPostgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}

}
