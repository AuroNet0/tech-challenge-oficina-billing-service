package com.oficina.billing.infrastructure.persistence.adapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.port.out.PagamentoRepositoryPort;
import com.oficina.billing.domain.model.Pagamento;
import com.oficina.billing.infrastructure.persistence.mapper.PagamentoMapper;
import com.oficina.billing.infrastructure.persistence.repository.PagamentoJpaRepository;

@Repository
@Transactional
public class PagamentoPersistenceAdapter implements PagamentoRepositoryPort {
    private final PagamentoJpaRepository repository;

    public PagamentoPersistenceAdapter(PagamentoJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Pagamento save(Pagamento pagamento) {
        return PagamentoMapper.toDomain(repository.saveAndFlush(PagamentoMapper.toEntity(pagamento)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Pagamento> findById(UUID id) {
        return repository.findById(id).map(PagamentoMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pagamento> findByOsId(UUID osId) {
        return repository.findByOsId(osId).stream().map(PagamentoMapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pagamento> findBySagaId(UUID sagaId) {
        return repository.findBySagaId(sagaId).stream().map(PagamentoMapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Pagamento> findByIdempotencyKey(String key) {
        return repository.findByIdempotencyKey(key).map(PagamentoMapper::toDomain);
    }
}
