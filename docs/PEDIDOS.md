# Pedidos da unidade e conversa vinculada (CP-033, sem anexos)

Flag: `CP_REQUESTS_ENABLED` (padrão `false`; ligada na homologação em 29/10/2026). V017 aplicada na homologação
(`rpnrqfrfwywglvyykgrg`) em 29/10/2026; produção ainda sem migrations. Ver [STATUS.md](STATUS.md).
Anexos (CP-017) e aviso efetivo à unidade (CP-034/CP-035) ficam para tickets próprios.

## Quem faz o quê

| Pessoa | Pode | Não pode |
| --- | --- | --- |
| Unidade (morador ou responsável com vínculo vigente em unidade ativa e conta ativa) | Abrir pedido para a própria unidade (categoria, título e descrição, com `Idempotency-Key`), ver os pedidos da unidade e conversar neles | Ver pedido de outra unidade, mesmo pelo ID; ver comentário interno; mudar andamento, responsável, prazo ou resolução; escrever em pedido fechado |
| Gestão (`MANAGER`, capacidade `requests.manage` com AAL2) | Ver os pedidos do condomínio com filtro por andamento, definir responsável da equipe (gestão ou portaria vigente) e prazo comunicado, mudar o andamento pela versão lida, registrar a resolução e escrever mensagem visível ou comentário interno | Mudar categoria, título, descrição, unidade ou autoria do pedido; apagar ou editar mensagem; reabrir pedido fechado |

Dependente e proprietário ficam fora deste recorte (nenhum fluxo concede permissão ao dependente).

## Rotas

| Operação | Rota |
| --- | --- |
| Pedidos da unidade (até 100) | `GET /v1/tenants/{tenantId}/units/{unitId}/requests` |
| Abrir pedido | `POST /v1/tenants/{tenantId}/units/{unitId}/requests` |
| Pedido e conversa visível | `GET /v1/tenants/{tenantId}/requests/{requestId}` |
| Mensagem da unidade | `POST /v1/tenants/{tenantId}/requests/{requestId}/messages` |
| Pedidos do condomínio (até 100, filtro `state`) | `GET /v1/tenants/{tenantId}/management/requests` |
| Pedido com toda a conversa e a equipe | `GET /v1/tenants/{tenantId}/management/requests/{requestId}` |
| Mensagem ou comentário interno da equipe | `POST /v1/tenants/{tenantId}/management/requests/{requestId}/messages` |
| Andamento, responsável, prazo e resolução | `POST /v1/tenants/{tenantId}/management/requests/{requestId}/state` |

## Regras

- **Categorias**: manutenção, limpeza, segurança, barulho, administrativo e outros. Título de 3 a 120 caracteres,
  descrição e mensagens de até 2.000 e resolução de até 1.000, com a mesma regra de texto simples do Java e da web.
- **Andamento** pela versão lida (+1): aberto → em triagem, em andamento ou fechado; em triagem → em andamento,
  aguardando a unidade, resolvido ou fechado; em andamento → aguardando a unidade, resolvido ou fechado; aguardando a
  unidade → em andamento, resolvido ou fechado; resolvido → em andamento (reaberto) ou fechado. Fechado é final.
- **Resolução**: obrigatória para concluir e gravada também na conversa como mensagem visível da equipe. Reaberto, o
  pedido perde a resolução vigente, que continua no histórico. Prazo comunicado vai de hoje até um ano, no fuso do
  condomínio; a unidade vê o prazo e se há responsável, não quem é.
- **Conversa**: cada mensagem pertence ao pedido em que foi gravada, com autor, nome do vínculo e horário gravados pelo
  banco; não muda nem é apagada. Comentário interno é sempre da equipe e nunca aparece para a unidade. Mensagem e
  fechamento do mesmo pedido ficam em série (trava consultiva do pedido); pedido fechado não recebe mensagem.
- **Banco (V017)**: `cp.service_requests` e `cp.request_messages` com RLS (gestão por capacidade; unidade pelo vínculo
  vigente, em `cp.request_unit_member`), grants por coluna, gatilhos de abertura, transição e conversa, eventos
  `REQUEST_*` no outbox (sem consumidor neste recorte) e trilha `REQUEST_*` em nome da própria conta. A guarda de
  inicialização recusa runtime com `DELETE`, alteração de mensagem ou grant nas colunas preenchidas pelo banco.

## Telas web

- Morador, Início: seção "Pedidos" com os pedidos da unidade, abertura com envio incerto preso à mesma chave, conversa
  e mensagens.
- Gestão, seção Pedidos: lista com filtro por andamento, detalhe com conversa completa, comentário interno, responsável,
  prazo, andamento e resolução.

## Pendências

- Ensaio com Auth real, gestão e portaria nomeadas; aplicação na produção pelo executor próprio.
- Anexos (CP-017), aviso à unidade pela caixa interna e push (CP-034/CP-035), tempo real (CP-036).
- Paginação além dos 100 pedidos e das 200 mensagens mais recentes, retenção e acesso do proprietário e do dependente.
