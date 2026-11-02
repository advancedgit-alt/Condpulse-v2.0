# Plano de finalização e validação — CondPulse HML

Rodada de 01/11/2026, America/Sao_Paulo. Fonte operacional: [ESTADO_ATUAL.md](ESTADO_ATUAL.md).
Este plano organiza o trabalho restante; não declara o PRD inteiro entregue nem promove produção.

## Como executar o ciclo

Para cada item: conferir código/estado remoto → reproduzir → corrigir na branch → testar → revisar PR → integrar → publicar quando necessário → conferir a jornada real → registrar evidência e próximo item.
Não encerrar um item somente por CI ou readiness. Marcar separadamente IMPLEMENTADO, PUBLICADO, HOMOLOGADO e BLOQUEADO.
Se uma tentativa não altera o estado, investigar antes de repetir; não reenviar cegamente uma gravação.
Uma falha nova reabre o item correspondente. Só repetir testes amplos por nova alteração, falha ou dúvida material.
O ciclo é trabalho ativo da sessão; este documento não cria serviço permanente, agendamento ou agente em segundo plano.
Credenciais, TOTP, CAPTCHA, equipamentos e eventual custo adicional podem exigir participação do responsável; registrar o bloqueio e avançar nas tarefas independentes possíveis.

## Prioridade acrescentada pelo responsável

Controle de prestadores/visitantes/festas: horário editável (exemplo08–17), portaria no vencimento, morador após30min, restrição de nova entrada após60min, liberação responsável registrada e ativação/desativação por condomínio. ADR-0014 divide a entrega em configuração/associação com reserva, avisos, identidade/restrição/liberação e confirmação física. Esta prioridade entra na fase3/4; nenhuma dessas extensões é declarada homologada por este documento. Consulte [ADR-0014](adr/0014-controle-de-permanencia-e-reentrada.md).

## Fases e critérios

| Fase | Trabalho restante | Evidência exigida para encerrar |
| --- | --- | --- |
| 1 — Publicação | Publicar a fonte revisada com convite de gestor adicional e V056 já aplicada; conferir API/worker e preservar pools/flags | Inspect válido por fonte; deploy da API com readiness200/UP e worker estável; recibos dos workflows |
| 2 — Perfis | Login da conta principal; convite da segunda conta já autorizada, aceite PENDING, aprovação pelo gestor atual, MFA próprio da segunda conta; esta aprova morador A-101 e portaria da primeira conta | Vínculos ACTIVE pela aplicação, identidade real da própria conta, negação de autoaprovação preservada e contextos operacionais visíveis |
| 3 — Jornadas reais | Portaria/visitas/encomendas/ocorrências; entrada/saída/permanência; fila de reservas; notificações internas; parceiro, incidentes e log | Estado persistido, autoria/log e leitura no perfil correto; conflito/replay/acesso indevido negados; sem duplicação; evidência por percurso |
| 4 — Lacunas funcionais | Fechar o escopo do piloto com o PRD original e complementar: Log na gestão, console/fila/SLA, passagem/checklist de turno e demais itens abaixo | Ticket com implementação, autorização, estados de falha e critérios de aceite testados; itens externos explicitamente bloqueados |
| 5 — Confiabilidade | Profiling/p95, carga representativa, recuperação de conta, aparelho real, cópia do Storage, restauração cronometrada e operação | Metas medidas e resultados publicados; restauração em alvo isolado, sem apagar a HML; RPO/RTO demonstrados |
| 6 — Piloto | Consolidar roteiro, acessos, evidências, limitações, responsáveis e triagem de falhas | Todos os critérios obrigatórios do piloto aceitos; uso acompanhado em HML; nenhum anúncio de aceite de produção sem seus próprios gates |

## Fase 1 — execução

Usar a fonte e os recibos vigentes de [ESTADO_ATUAL.md](ESTADO_ATUAL.md) e [loop-validation-hml-20261101.json](../evidence/loop-validation-hml-20261101.json).
V056 não deve ser reaplicada por SQL manual. Inspeções/deploys usam o SHA atual e o ACK correspondente.
API/worker conservam seus logins existentes; nenhum apply/rotação, aumento de pool ou contratação é necessário para esta publicação.
SMTP Resend do Auth já configurado; não pedir novamente provedor ou chaves.

## Fase 2 — acessos específicos já confirmados

- Condomínio demo geração1 e parceiro fictício, sem concessões em condomínio real.
- Gestão e PARTNER_ADMIN da primeira conta já ACTIVE; não repetir esses convites.
- RESIDENT da Torre A fictícia,101 e CONCIERGE: aceitos/PENDING, aguardam outro gestor ativo com AAL2.
- Segunda conta de teste existente como gestor adicional: já autorizada. Convite/aceite/aprovação e seu próprio MFA ainda precisam ser executados.
- MANAGER adicional não possui unidade. Aceite local cria PENDING; o outro gestor aprova. SUPPORT continua fora do convite.
- Não fabricar ator, editar status/papel por SQL ou remover a proteção de autoaprovação/último gestor.
- Sessão expirada exige novo login normal. Configuração de nova credencial de segundo fator é realizada pelo responsável, sem expor QR/segredo ao agente.

## Fase 3 — percursos pendentes

1. Encomenda sintética: registrar, consultar como unidade correta, confirmar retirada, tentar repetição e verificar uma única entrega/auditoria.
2. Visita: janela válida pelo relógio/fuso do condomínio, chegada, decisão pelo morador correto e recusa de outra unidade; entrada/saída e histórico. Preservar convites anteriores e criar nova janela sintética se a antiga expirar.
3. Permanência: entrada abre; alertas previstos/vencidos/N1/N2 surgem pelo worker sem duplicação; morador confirma saída e gestão/portaria conferem autoria e fechamento do dia.
4. Fila de reservas: disputa real pelo slot, cancelamento, oferta/aceite e expiração; sem reserva dupla nem contagem otimista.
5. Ocorrência/parceiro: alcance por contrato/escala, incidente, vínculo de evento, responsável e encerramento; dossiê/exportação somente com capacidade e motivo.
6. Log: busca, integridade, evento de leitura e exportação. A leitura anterior foi feita por conta também MANAGER local; ainda não prova identidade exclusivamente de parceiro.
7. Revalidar após correção os percursos já comprovados que forem afetados: reserva, pedido/conversa/anexo, comunicado e caixa interna.
E-mail de negócio/push não são equivalentes à caixa interna: efeitos externos ainda não implementados não podem ser anunciados como entregues.
Fluxo físico real requer adaptador/modelo/firmware homologado; evento sintético não comprova acionamento de equipamento.

## Fase 4 — implementação adicional e alcance do PRD

O PRD complementar traz entregas além do núcleo residencial já testado. Não reduzir toda essa etapa a uma correção de tela:

| Frente | Referência | Situação/ação necessária |
| --- | --- | --- |
| Hierarquia, log, comando e permanências | CPX-001–019 | Há implementação; completar homologação por perfil e lacunas de apresentação/integração. Gestão ainda sem seção própria de Log; o semáforo v1 não agrega permanências |
| Console e turno | CPX-020–022 | Conferir/implementar fila única em tempo real, atendimento/redistribuição/SLA e abertura remota com motivo/dupla confirmação; painéis ainda incompletos |
| Operação da portaria | CPX-023–026 | Passagem de turno com aceite, checklist, ronda QR e achados/perdidos, com evidência por requisito |
| Abertura/fechamento | CPX-027–030 | Gateway/adaptador homologado, eclusa e sensores de porta, controle de abertura pelo app; equipamento/integrador são dependências externas |
| Bloqueio e encomendas avançadas | CPX-031–034 | Conferir lacunas de dupla aprovação/lista de bloqueio, alerta e lembrete/lote/busca; não confundir bloqueio de sujeito existente com entrega integral desta frente |
| Experiência do morador | CPX-035–039 | Aceite em iOS/Android e inventário de delivery/viagem, pets/veículos, documentos versionados e interfone; tecnologia do interfone ainda requer decisão |
| Eventos e emergência | CPX-040–041 | Convidados no console e emergência/coação com roteamento/log, sem simular acionamento real |
| Plataforma do parceiro | CPX-042–045 | Confrontar assistente/white-label, cobrança/régua e LGPD com o código e critérios completos; módulos parciais não equivalem ao aceite desses tickets |
| Demo | CPX-046 | Geração1 carregada; dados e escopo sintéticos preservados; não resetar sem pedido posterior |

A tabela é fila de conferência/entrega, não afirma que cada subitem está totalmente ausente.
O ERP completo de administradoras (D1–D9) é outra frente documental e funcional; não atribuir sua conclusão ao piloto do app morador/portaria.
Não alterar app-advanced nesta execução.

## Fase 5 — metas e recuperação

- Carga descartável anterior:100rps/120s,11.999chamadas,zero erros/descarte,p95=805,6ms,p99=1.738,3ms. Metas do PRD não foram atendidas; não representa capacidade da hospedagem.
- Perfil de leituras<=400ms p95 e comandos<=800ms p95, portaria<=3s; identificar gargalo antes de aumentar infraestrutura/pools.
- Recovery real do início ao fim; navegador mobile e aparelho real são evidências diferentes. CI mobile não comprova execução em iOS/Android.
- Backup físico disponível de01/11 07:52:12UTC: anterior à carga e sem bytes do Storage. Ainda não há aceite de restore/RPO/RTO.
- Restaurar em ambiente isolado, medir banco e objetos, conferir integridade/downloads/contas/filas e ensaiar rollback. Nenhum restore destrutivo da HML ou de produção está autorizado por este plano.
- RPO banco<=15min e objetos<=1h/RTO<=4h exigem mecanismo e exercício compatíveis; backup diário sozinho não atende. Contratação/PITR/adicionais pagos exigem aceite específico após cotação.
- Hardware/firmware: ensaio com integrador, segurança e contingência; não ativar facial/abertura real para passar teste.

## Fase 6 — saída para testes

Entregar roteiro com quais módulos estão disponíveis, quais perfis usam cada função e como registrar falhas.
Preservar a carga sintética para revisão e manter triagem com versão, passos, esperado/observado, evidência e correção.
A liberação para testes na HML não aponta tráfego para o projeto operacional de produção.
Promoção de produção tem fonte/digest, migrations, credenciais, backups, smoke, worker e observação próprios; só depois dos gates correspondentes.

## Registro por ciclo

Cada registro deve conter: fase/ticket, ambiente, fonte/PR, reprodução, mudança, testes efetivamente executados, resultado remoto, limitação e próximo item.
Não repetir como pendência SMTP, V053–V056, demo carregado ou operações já atestadas. Consulte [STATUS.md](STATUS.md) para histórico e [ESTADO_ATUAL.md](ESTADO_ATUAL.md) para o estado vigente.
