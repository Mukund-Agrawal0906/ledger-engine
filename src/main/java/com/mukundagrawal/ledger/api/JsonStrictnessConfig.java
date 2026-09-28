package com.mukundagrawal.ledger.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Money APIs should reject ambiguous input rather than guess (docs/LLD.md §6): unknown fields (a typo
 * like {@code ammount} must not be silently dropped), fractional amounts ({@code 100.5} must not become
 * {@code 100}) and stringly-typed numbers ({@code "100"}).
 */
@Configuration(proxyBeanMethods = false)
class JsonStrictnessConfig {

	@Bean
	JsonMapperBuilderCustomizer strictMoneyJson() {
		return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.withCoercionConfig(LogicalType.Integer, cfg -> cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
	}

}
