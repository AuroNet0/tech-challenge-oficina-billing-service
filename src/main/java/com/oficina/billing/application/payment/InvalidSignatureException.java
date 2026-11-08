package com.oficina.billing.application.payment;

public class InvalidSignatureException extends RuntimeException {
    public InvalidSignatureException() { super("Notificacao nao autenticada"); }
}
