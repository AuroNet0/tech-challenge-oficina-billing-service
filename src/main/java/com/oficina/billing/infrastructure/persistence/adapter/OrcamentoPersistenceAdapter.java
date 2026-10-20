package com.oficina.billing.infrastructure.persistence.adapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.port.out.OrcamentoRepositoryPort;
import com.oficina.billing.domain.model.Orcamento;
import com.oficina.billing.infrastructure.persistence.mapper.OrcamentoMapper;
import com.oficina.billing.infrastructure.persistence.repository.OrcamentoJpaRepository;

@Repository
@Transactional
public class OrcamentoPersistenceAdapter implements OrcamentoRepositoryPort {
    private final OrcamentoJpaRepository repository;

    public OrcamentoPersistenceAdapter(OrcamentoJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Orcamento save(Orcamento orcamento) {
        return OrcamentoMapper.toDomain(repository.saveAndFlush(OrcamentoMapper.toEntity(orcamento)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Orcamento> findById(UUID id) {
        return repository.findById(id).map(OrcamentoMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Orcamento> findByOsId(UUID osId) {
        return repository.findByOsId(osId).map(OrcamentoMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Orcamento> findBySagaId(UUID sagaId) {
        return repository.findBySagaId(sagaId).stream().map(OrcamentoMapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByOsId(UUID osId) {
        return repository.existsByOsId(osId);
    }
}
