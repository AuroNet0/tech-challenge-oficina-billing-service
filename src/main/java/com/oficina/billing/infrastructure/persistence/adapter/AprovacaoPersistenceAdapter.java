package com.oficina.billing.infrastructure.persistence.adapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.port.out.AprovacaoRepositoryPort;
import com.oficina.billing.domain.model.Aprovacao;
import com.oficina.billing.infrastructure.persistence.mapper.AprovacaoMapper;
import com.oficina.billing.infrastructure.persistence.repository.AprovacaoJpaRepository;

@Repository
@Transactional
public class AprovacaoPersistenceAdapter implements AprovacaoRepositoryPort {
    private final AprovacaoJpaRepository repository;
    private final EntityManager entityManager;

    public AprovacaoPersistenceAdapter(AprovacaoJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Aprovacao save(Aprovacao aprovacao) {
        entityManager.persist(AprovacaoMapper.toEntity(aprovacao));
        entityManager.flush();
        return aprovacao;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Aprovacao> findByOrcamentoId(UUID orcamentoId) {
        return repository.findByOrcamentoId(orcamentoId).map(AprovacaoMapper::toDomain);
    }
}
