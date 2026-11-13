package com.hogar.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Expone un {@link Clock} en UTC para que el código de negocio nunca llame a {@code now()}
 * directamente y los tests puedan fijar el tiempo.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
