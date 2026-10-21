package com.oficina.billing.application.payment;

public class IntegrationUnavailableException extends RuntimeException {
    public IntegrationUnavailableException() { super("Integracao externa desabilitada"); }
}
