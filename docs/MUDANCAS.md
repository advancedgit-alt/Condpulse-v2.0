# Mudança e entrega de móveis (CP-042)

Atrás de `CP_REQUESTS_ENABLED` (a mesma flag dos pedidos da unidade). Banco: V025 (`cp.move_rules`, `cp.move_requests`).

## Regras

- **Regras do condomínio**: a gestão (`requests.manage`, segundo fator) publica o texto exibido às unidades, os dias da
  semana permitidos, o horário local (início e fim), a antecedência mínima (0 a 720 h) e a duração máxima (30 a 720 min).
  Cada alteração cria uma nova versão; alteração concorrente responde `MOVE_RULES_CHANGED`.
- **Pedido**: morador ou responsável da unidade (dependente não pede nem vê) escolhe o tipo (mudança de entrada, de
  saída ou entrega de móveis), o período, o responsável/transportadora, a placa (opcional, 5 a 8 letras e números) e os
  itens (opcional), e aceita a versão vigente das regras. O banco confere, no fuso do condomínio, dia permitido, início e
  fim no mesmo dia e dentro do horário, antecedência mínima e duração máxima (`MOVE_WINDOW_OUTSIDE_RULES`). Regras
  alteradas depois da leitura respondem `MOVE_RULES_CHANGED` e a tela pede novo aceite. Chave de operação obrigatória.
- **Decisão**: a gestão aprova (nota opcional) ou recusa (motivo obrigatório) antes do início, pela versão lida.
  A fila "Aguardando decisão" mostra só os pedidos ainda decidíveis; pedido sem decisão cujo período começou sai
  da fila e continua na lista geral (não bloqueia os seguintes).
- **Cancelamento**: a unidade retira o pedido aguardando ou aprovado antes do início; a gestão cancela com motivo até o fim.
- **Portaria**: vê somente os pedidos aprovados do dia escolhido (até 14 dias por consulta), com responsável, placa e itens.
- **O que a aprovação não faz**: não reserva elevador nem área (não existem no sistema) e não autoriza obra.

## API e web

- `GET /v1/tenants/{t}/move-rules`, `PUT /v1/tenants/{t}/management/move-rules`,
  `GET|POST /v1/tenants/{t}/units/{u}/moves` (`Idempotency-Key` no POST), `POST /v1/tenants/{t}/moves/{id}/cancel`,
  `GET /v1/tenants/{t}/management/moves?state=`, `POST /v1/tenants/{t}/management/moves/{id}/decision`,
  `GET /v1/tenants/{t}/concierge/moves?from=&until=`.
- Web: seção "Mudança e entrega de móveis" no início da unidade (regras, pedido com aceite, retirada com confirmação),
  aba "Mudanças" da gestão (publicar/editar regras, filtro, aprovar, recusar, cancelar) e "Mudanças aprovadas" na aba
  Visitas da portaria (escolha do dia).
- Celular (morador e responsável): regras, pedido com aceite, chave mantida depois de resultado incerto e retirada com
  confirmação; dependente e recurso desligado escondem a seção.

## Evidências

`tests/database/run.py` (`move_checks`), `MoveJdbcTest`, `DatabaseRoleGuardJdbcTest`; web `api/moves.test.ts` e
`e2e/moves.spec.ts` (todos os botões das três telas); celular `__tests__/moves.test.tsx`.

## Pendentes

Aviso à portaria e push. A decisão (`MOVE_APPROVED`/`MOVE_REJECTED`) vira aviso `MOVE_DECIDED` na caixa interna de morador e
responsável desde a V031, enquanto a decisão vale; a entrega depende do worker contínuo, que não está implantado —
BLOQUEADO na implantação do worker. Ensaio em aparelho real e com Auth real.
