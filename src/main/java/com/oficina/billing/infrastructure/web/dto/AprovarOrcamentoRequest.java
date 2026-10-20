package com.oficina.billing.infrastructure.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AprovarOrcamentoRequest(
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*")
        @Schema(description = "Identificador informado pelo solicitante; nao comprova identidade nem autorizacao")
        String responsavelId) {
}
