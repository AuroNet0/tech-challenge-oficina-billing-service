# language: pt
Funcionalidade: Fluxo distribuído da oficina
  Cenário: Conclusão de uma ordem de serviço com pagamento aprovado
    Dado que existem usuários autenticados no Auth, uma OS aberta e um mecânico atribuído
    Quando o diagnóstico é iniciado e concluído, o orçamento é notificado e aprovado, o reparo é iniciado e concluído e o provedor confirma o pagamento
    Então o pagamento está CONFIRMED, a OS FINALIZADA e a Saga COMPLETED
