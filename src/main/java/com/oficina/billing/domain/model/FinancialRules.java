package com.oficina.billing.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.oficina.billing.domain.exception.DomainException;

public final class FinancialRules {
    private FinancialRules() {
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new DomainException(message);
        }
    }

    public static <T> T required(T value, String field) {
        require(value != null, field + " obrigatorio");
        return value;
    }

    public static String text(String value, int maxLength, String field) {
        require(value != null && !value.isBlank() && value.length() <= maxLength, field + " invalido");
        return value;
    }

    public static String optionalText(String value, int maxLength, String field) {
        return value == null ? null : text(value, maxLength, field);
    }

    public static BigDecimal decimal(BigDecimal value, int precision, int scale, String field) {
        required(value, field);
        require(value.signum() >= 0 && value.scale() >= 0 && value.scale() <= scale,
                field + " negativo ou escala invalida");
        require(value.setScale(scale, RoundingMode.UNNECESSARY).precision() <= precision,
                field + " excede a precisao");
        return value;
    }

    public static BigDecimal money(BigDecimal value) {
        required(value, "valor");
        try {
            return decimal(value.setScale(2, RoundingMode.UNNECESSARY), 19, 2, "valor");
        } catch (ArithmeticException exception) {
            throw new DomainException("Valor BRL exige centavos exatos, sem arredondamento");
        }
    }
}
