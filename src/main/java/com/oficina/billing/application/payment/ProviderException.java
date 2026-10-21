package com.oficina.billing.application.payment;

public class ProviderException extends RuntimeException {
    private final boolean retryable;
    public ProviderException(boolean retryable) {
        super(retryable ? "Provedor temporariamente indisponivel" : "Operacao do provedor exige revisao");
        this.retryable = retryable;
    }
    public boolean retryable() { return retryable; }
}
