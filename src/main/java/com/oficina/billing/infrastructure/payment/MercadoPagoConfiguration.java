package com.oficina.billing.infrastructure.payment;

import com.oficina.billing.application.payment.PaymentIntegrationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MercadoPagoProperties.class)
public class MercadoPagoConfiguration {
    @Bean
    PaymentIntegrationPolicy paymentIntegrationPolicy(MercadoPagoProperties properties) {
        properties.validate();
        return new PaymentIntegrationPolicy(properties.isEnabled(), properties.isReconciliationEnabled(),
                properties.getLeaseDuration(), properties.getSyncInterval(), properties.getBatchSize());
    }
}
