package com.oficina.billing.infrastructure.persistence.mapper;

import com.oficina.billing.domain.model.*;
import com.oficina.billing.infrastructure.persistence.entity.*;

public final class AprovacaoMapper {
    private AprovacaoMapper() {
    }

    public static AprovacaoEntity toEntity(Aprovacao domain) {
        var entity = new AprovacaoEntity();
        entity.id = domain.id();
        entity.orcamentoId = domain.orcamentoId();
        entity.osId = domain.osId();
        entity.status = domain.status();
        entity.decisionAt = domain.decisionAt();
        entity.responsavelId = domain.responsavelId();
        entity.motivo = domain.motivo();
        entity.correlationId = domain.correlationId();
        entity.createdAt = domain.createdAt();
        return entity;
    }

    public static Aprovacao toDomain(AprovacaoEntity entity) {
        return new Aprovacao(entity.id, entity.orcamentoId, entity.osId, entity.status, entity.decisionAt, entity.responsavelId, entity.motivo, entity.correlationId, entity.createdAt);
    }
}
