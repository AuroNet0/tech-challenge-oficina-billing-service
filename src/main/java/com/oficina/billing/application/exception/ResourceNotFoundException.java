package com.oficina.billing.application.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Recurso nao encontrado");
    }
}
