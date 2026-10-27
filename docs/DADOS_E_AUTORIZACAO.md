# Modelo e autorização
O schema de fundação não implementa todos os módulos do PRD.
platform.accounts armazena subject de Auth e estado, sem alterar auth.users.
platform.tenants guarda condomínio/fuso/estado.
cp.blocks,units,memberships,amenities,reservations,parcels,parcel_handoffs,arrival_requests,
audit_log,outbox,idempotency_keys formam a fatia de fundação.
Futuros módulos usam migrations adicionais e FKs compostas.

## Contexto
JWT válido -> conta ativa -> vínculo vigente -> capacidade/unidade/AAL -> transação.
set_config com true localiza ator/tenant ao commit/rollback e usa a mesma conexão.
A resolução inicial de memberships permite somente o próprio account_id.
Catálogo de tenants só aparece para quem tem vínculo ativo; worker recebe metadados mínimos.
RLS de tenant não substitui autorização por unidade ou privacidade pessoal.
Schemas de negócio não são concedidos a anon/authenticated nem expostos pela Data API.

## Integridade
Reservas bloqueantes HELD/PENDING_APPROVAL/CONFIRMED: EXCLUDE por tenant/área/ocupação.
Intervalo [início,fim) permite adjacência e inclui limpeza confiável calculada pela API.
Cota entre áreas precisa de trava de unidade, além da constraint de horário.
FKs compostas impedem unidade/área de outro tenant.
UNIQUE em handoff impede segunda retirada. Transação coordena handoff e estado da encomenda.
Chegada usa update condicional de estado/versão/prazo.
Auditoria é insert-only para aplicação. Outbox guarda evento e lease, sem segredo/biometria.
Idempotência é escopada a ator e tenant e não compartilha resposta entre usuários.

## Roles
cp_api e cp_worker são grupos NOLOGIN, sem SUPERUSER/BYPASSRLS/DDL.
Login e senha de execução são provisionados no cofre e associados ao grupo necessário.
Migrador é separado; runtime nunca usa dono das tabelas nem credencial service_role.
Privilégios de tabelas futuras são concedidos explicitamente.
Toda tabela cp usa ENABLE/FORCE RLS, inclusive evidências e filas.
