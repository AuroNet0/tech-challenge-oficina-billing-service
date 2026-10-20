package com.oficina.billing.application.usecase;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.exception.ResourceNotFoundException;
import com.oficina.billing.application.port.in.ConsultarPagamentoPort;
import com.oficina.billing.application.port.out.PagamentoRepositoryPort;
import com.oficina.billing.domain.model.Pagamento;

@Service
@Transactional(readOnly = true)
public class ConsultarPagamentoUseCase implements ConsultarPagamentoPort {
    private final PagamentoRepositoryPort pagamentos;

    public ConsultarPagamentoUseCase(PagamentoRepositoryPort pagamentos) {
        this.pagamentos = pagamentos;
    }

    @Override
    public Pagamento porId(UUID id) {
        return pagamentos.findById(id).orElseThrow(ResourceNotFoundException::new);
    }

    @Override
    public List<Pagamento> porOsId(UUID osId) {
        return pagamentos.findByOsId(osId);
    }
}
