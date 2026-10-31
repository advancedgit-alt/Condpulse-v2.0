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

A consulta autenticada de ocupação
`GET /v1/tenants/{tenantId}/amenities/{amenityId}/availability` exige capacidade de
criar reservas em uma unidade própria vigente. Reutiliza a transação e as verificações
do contexto; um vínculo apenas de cancelamento ou administrativo não concede esse
acesso. A área ativa precisa ter política válida e não exigir aprovação neste subset.
O retorno agrega a ocupação da área no tenant autorizado, sem IDs de reservas,
unidades de terceiros, nomes, atores ou status individuais. Ecoa somente o contexto
solicitado e entrega intervalos sem identificação, fuso, período e horário observado
no servidor, com Cache-Control no-store. Não é um endpoint público nem substitui a
listagem identificada de reservas da própria unidade.

## Integridade
Reservas bloqueantes HELD/PENDING_APPROVAL/CONFIRMED: EXCLUDE por tenant/área/ocupação.
Intervalo [início,fim) permite adjacência e inclui limpeza confiável calculada pela API.
Cota entre áreas precisa de trava de unidade, além da constraint de horário.
FKs compostas impedem unidade/área de outro tenant.
Na consulta de ocupação, BOOKING e BLOCK nos estados HELD/PENDING_APPROVAL/CONFIRMED
usam occupied_from/occupied_until, incluindo limpeza, como a exclusão GiST.
expires_at vencido não remove um bloqueio antes da transição efetiva de estado.
A janela obrigatória tem até31 dias e precisão máxima de microssegundos; os intervalos
são recortados para [from,until), ordenados e unidos, inclusive quando adjacentes.
Mais de1000 ocupações de origem resultam em503, sem truncamento ou resposta parcial.
Uma leitura sem ocupações não garante criação: regras, cota e conflito são conferidos
novamente na confirmação. A consulta não amplia grants ou altera RLS/DDL, e não
implementa criação de bloqueios, aprovação ou expiração automática.
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

## Proprietário de unidade alugada (V034)
Enquanto a gestão mantiver a unidade marcada como alugada (`cp.unit_rentals`), o vínculo OWNER perde a visão de todas
as tabelas com `unit_id` daquela unidade (política RESTRICTIVE `rental_owner_hidden`) e dos avisos, e só administra os
ocupantes e os convites de inquilino dela. Detalhes em `docs/PROPRIETARIO_INQUILINO.md`.
