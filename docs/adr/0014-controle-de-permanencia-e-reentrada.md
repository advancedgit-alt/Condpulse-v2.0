# ADR-0014 — Horários, escalada e reentrada de visitantes/prestadores

Status: EM IMPLEMENTAÇÃO. Pedido expresso do responsável em 01/11/2026; não confundir com publicação ou homologação.

O controle é configurado por condomínio e categoria. Exemplo solicitado: prestador das08h às17h; no vencimento a portaria recebe uma pendência para localizar/notificar; +30min avisa o morador; +60min impede nova entrada até liberação responsável registrada na portaria. Cada etapa tem prazo configurável e o controle pode ser desligado. Entrada/saída e eventos continuam registrados quando alertas/bloqueios estão desligados. Nunca bloquear saída.

Reutilizar V055, sem modificar migration aplicada. Horários e tolerâncias são calculados no relógio/fuso do condomínio; o prazo e a categoria ficam fixados na entrada. Alterar uma regra não reescreve o histórico. Desativar suspende novas escaladas e a aplicação da restrição; reativar pode reaplicar pendências ainda não liberadas, e a interface deve avisar.

Convite de festa já referencia reserva/área e termina com a reserva (V021). A permanência precisa carregar esse vínculo para mostrar a área/festa e a unidade responsável. Registrar categoria EVENT_GUEST para configuração independente de prestadores e visitantes comuns.

Bloqueio de reentrada é uma restrição de visita, separada de bloqueio geral do sujeito e de um contexto residencial. Encerrar a permanência por saída não apaga uma restrição de reentrada já gerada. Uma correção válida de entrada anula sua restrição derivada, preservando os eventos.

Liberação: morador/responsável vigente da unidade de origem solicita, aceita a versão do termo de responsabilidade e informa motivo; portaria com AAL2 confirma. Nenhuma unidade diferente, dependente sem autoridade, conta revogada ou portaria sozinha substitui esse aceite. Repetição da mesma ação é idempotente; as duas autorias e os horários vêm do servidor. Permissão nova continua exigindo convite/janela vigente; liberar restrição não cria autorização irrestrita.

Aviso ao morador usa a caixa interna autenticada, com outbox/retry e leitura distinta de disponibilidade. E-mail/push externo só pode ser anunciado quando um consumidor real existir e o canal tiver evidência. Portaria recebe pendência operacional identificável e registra ciência/providência no histórico.

Identidade: nome/final de documento não identificam unicamente uma pessoa. O bloqueio deve seguir o cadastro verificado/sujeito do visitante, inclusive ao reaproveitar prestador recorrente. O modelo atual cria cadastros de visita; não afirmar bloqueio universal de uma pessoa que foi recadastrada sem uma vinculação verificada. A associação entre cadastros é ação explícita da portaria, auditada, nunca inferida por semelhança de nome. Esse requisito precisa de teste antes do aceite integral.

Equipamento: a decisão canônica do servidor deve recusar o contexto de visitante restrito. Leitor offline/cached não fica automaticamente bloqueado por mudar uma linha: publicação/revogação e confirmação do adaptador/modelo/firmware são necessárias. Exibir ordem/decisão do servidor separada de aplicação física confirmada. O hardware piloto permanece um gate externo; não fabricar evento facial nem declarar acionamento comprovado por fixture.

Testes obrigatórios: desligado/ligado, fronteiras08/17/+30/+60 e fuso, reserva/festa, worker atrasado/repetido/concorrente, nenhum aviso após saída, atraso com saída antes da passada, restrição persistente, convite novo sem contornar identidade, unidade/ator/AAL incorretos, responsabilidade sem aceite, repetição e conflito, liberação por duas contas, correções, isolamento de tenant, negação manual/canônica e estado de sincronização física. Fixtures e relógio sintéticos somente em ambiente descartável; não desativar proteções para fabricar sucesso.
