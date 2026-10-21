package com.oficina.billing.infrastructure.payment;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billing.mercado-pago")
public class MercadoPagoProperties {
    private boolean enabled;
    private boolean reconciliationEnabled;
    private boolean allowInsecureLocal;
    private String accessToken = "";
    private String webhookSecret = "";
    private URI apiBaseUrl = URI.create("https://api.mercadopago.com");
    private URI notificationUrl;
    private URI successUrl;
    private URI failureUrl;
    private URI pendingUrl;
    private Duration httpTimeout = Duration.ofSeconds(5);
    private Duration signatureTolerance = Duration.ofMinutes(5);
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration syncInterval = Duration.ofMinutes(1);
    private int batchSize = 10;

    public void validate() {
        if (httpTimeout.isNegative() || httpTimeout.isZero() || httpTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || signatureTolerance.isNegative() || leaseDuration.compareTo(httpTimeout.multipliedBy(3)) <= 0
                || syncInterval.isNegative() || syncInterval.isZero() || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Configuracao de limites Mercado Pago invalida");
        }
        if (!enabled) return;
        if (accessToken.isBlank() || webhookSecret.length() < 16) {
            throw new IllegalArgumentException("Credenciais de teste Mercado Pago obrigatorias quando habilitado");
        }
        boolean local = allowInsecureLocal && "http".equals(apiBaseUrl.getScheme())
                && ("localhost".equals(apiBaseUrl.getHost()) || "127.0.0.1".equals(apiBaseUrl.getHost()));
        if ((!local && !("https".equals(apiBaseUrl.getScheme()) && "api.mercadopago.com".equals(apiBaseUrl.getHost())))
                || apiBaseUrl.getUserInfo() != null || apiBaseUrl.getQuery() != null
                || (apiBaseUrl.getPath() != null && !apiBaseUrl.getPath().isEmpty() && !"/".equals(apiBaseUrl.getPath()))) {
            throw new IllegalArgumentException("Base URL Mercado Pago nao permitida");
        }
        for (URI url : new URI[]{notificationUrl, successUrl, failureUrl, pendingUrl}) {
            if (url == null || !"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                    || url.getFragment() != null || "localhost".equals(url.getHost()) || "127.0.0.1".equals(url.getHost())) {
                throw new IllegalArgumentException("URLs HTTPS publicas obrigatorias para Checkout Pro");
            }
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isReconciliationEnabled() { return reconciliationEnabled; }
    public void setReconciliationEnabled(boolean value) { reconciliationEnabled = value; }
    public boolean isAllowInsecureLocal() { return allowInsecureLocal; }
    public void setAllowInsecureLocal(boolean value) { allowInsecureLocal = value; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String value) { accessToken = value; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String value) { webhookSecret = value; }
    public URI getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(URI value) { apiBaseUrl = value; }
    public URI getNotificationUrl() { return notificationUrl; }
    public void setNotificationUrl(URI value) { notificationUrl = value; }
    public URI getSuccessUrl() { return successUrl; }
    public void setSuccessUrl(URI value) { successUrl = value; }
    public URI getFailureUrl() { return failureUrl; }
    public void setFailureUrl(URI value) { failureUrl = value; }
    public URI getPendingUrl() { return pendingUrl; }
    public void setPendingUrl(URI value) { pendingUrl = value; }
    public Duration getHttpTimeout() { return httpTimeout; }
    public void setHttpTimeout(Duration value) { httpTimeout = value; }
    public Duration getSignatureTolerance() { return signatureTolerance; }
    public void setSignatureTolerance(Duration value) { signatureTolerance = value; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration value) { leaseDuration = value; }
    public Duration getSyncInterval() { return syncInterval; }
    public void setSyncInterval(Duration value) { syncInterval = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
}
