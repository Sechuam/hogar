package com.hogar.household.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InvitationProperties.class, PlanProperties.class})
class HouseholdConfig {
}
