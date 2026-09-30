package com.mukundagrawal.ledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the reconciliation schedule; {@code ledger.reconciliation.enabled=false} turns it off. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ledger.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

}
