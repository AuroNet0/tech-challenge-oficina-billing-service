package com.oficina.billing.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {
    @Bean
    Clock billingClock() {
        return Clock.systemUTC();
    }
}
