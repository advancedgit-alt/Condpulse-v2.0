# Gestão local do condomínio — API

Referências: PRD cap. 8, 10, 17, 18; CP-014, CP-018, CP-020, CP-021, CP-023; MVP fase 3.
Flag: `CP_MANAGEMENT_ENABLED` (padrão `false`, inclusive na Vercel).

## Autorização em duas barreiras

1. Serviço Java: conta ativa, vínculo vigente no condomínio, papel com a
   capacidade (`MANAGER`) e token `aal2`. Sem AAL2: `403 MFA_REQUIRED`.
   Sem papel: `403 STAFF_ACCESS_DENIED`. Outro condomínio: `404`.
2. Banco (V006): políticas RLS restritivas exigem `platform.has_capability(...)`
   para inserir/atualizar blocos, unidades e áreas e para ler auditoria. A
   capacidade só existe no contexto local da transação publicado pelo executor.
   Não há `DELETE`: unidades e áreas são inativadas.

## Rotas

| Rota | Capacidade | Observação |
| --- | --- | --- |
| `GET/POST .../management/blocks` | `units.manage` | Nome único por condomínio |
| `GET/POST .../management/units` | `units.manage` | Identificação única por bloco |
| `PUT .../management/units/{id}` | `units.manage` | `expectedVersion`; inativar bloqueia os vínculos da unidade na hora |
| `GET/POST .../management/amenities` | `amenities.manage` | Regra explícita, sem valores padrão permissivos |
| `PUT .../management/amenities/{id}` | `amenities.manage` | Mudança de regra cria nova `policyVersion`; reservas mantêm o retrato aceito |
| `GET .../management/audit-events` | `audit.read` | Cursor opaco, 30 por página (máx. 100) |

Toda alteração grava `cp.audit_log` na mesma transação (ator, ação, recurso,
motivo opcional), com uma ação por mudança: nova regra, inativação e renomeação
na mesma gravação geram três registros. Valores anteriores e novos ainda não são
gravados na auditoria. A web usa sessão BFF com CSRF; aplicativos usam Bearer.

No banco, `UPDATE` sem a capacidade é recusado com `42501` (403 na API) em vez de
não encontrar linhas. Blocos não têm `UPDATE` concedido. O `cp_api` ainda pode
gravar qualquer valor em `version`/`policy_version`: o incremento é garantido pela
API, não por gatilho no banco (pendência registrada).

## Antes de aplicar a V006 em banco com dados

A V006 cria `amenities_tenant_name` (nome único sem diferenciar maiúsculas) e
limita nomes de áreas (1–120) e blocos (1–60). O manifesto de onboarding foi
alinhado a esses limites e aos da gestão (capacidade até 10000, taxa até
10000000 centavos). Antes do Flyway remoto, rodar a consulta somente leitura
abaixo; qualquer linha retornada exige correção aprovada antes da migration:

```sql
select 'amenity_duplicate' as problem,tenant_id::text,lower(btrim(name)) as value,count(*)::text as detail
from cp.amenities group by tenant_id,lower(btrim(name)) having count(*)>1
union all
select 'amenity_name_length',tenant_id::text,id::text,length(btrim(name))::text
from cp.amenities where length(btrim(name)) not between 1 and 120
union all
select 'block_name_length',tenant_id::text,id::text,length(btrim(name))::text
from cp.blocks where length(btrim(name)) not between 1 and 60
union all
select 'amenity_limits',tenant_id::text,id::text,capacity::text||'/'||fee_cents::text
from cp.amenities where capacity>10000 or fee_cents>10000000;
```

## Vínculos e convites (V007)

| Rota | Capacidade | Observação |
| --- | --- | --- |
| `GET .../management/memberships?status=` | `memberships.manage` | Sem e-mail ou conta Auth |
| `POST .../memberships/{id}/decision` | `memberships.manage` | `APPROVE`→ACTIVE, `REJECT`→REVOKED (motivo obrigatório); versão lida |
| `POST .../memberships/{id}/revoke` | `memberships.manage` | Motivo obrigatório; evento `MEMBERSHIP_REVOKED`; nega o próprio vínculo de gestão e o último gestor ativo |
| `GET/POST .../management/invitations` | `memberships.manage` | Papéis residenciais e `CONCIERGE`; nunca `MANAGER` |
| `POST .../invitations/{id}/revoke` | `memberships.manage` | Somente convite pendente |
| `POST /v1/invitations/accept` | autenticado | Convidado com o mesmo e-mail cria só o próprio vínculo `PENDING` |

O convite tem validade de 1 a 14 dias. O token (32 bytes) aparece uma vez para a
gestão compartilhar pelo canal combinado; o banco guarda só o hash do token e o
hash do e-mail ligado ao convite. O aceite cria o espelho local da conta Auth já
existente, nunca usuário no provedor: o cadastro no Supabase Auth continua sendo
operação administrativa (cadastro público desligado). O vínculo só opera após
aprovação da gestão. No banco, o `INSERT` do convidado exige convite válido no
contexto local (`app.invitation_hash`), mesmo papel/unidade/nome, status
`PENDING` e o identificador do próprio convite (`invitation_id` único: cada convite
gera no máximo um vínculo, mesmo antes de concluído). A conclusão do convite só
aceita o vínculo do próprio convidado; o início da vigência é definido pelo banco.

Regras adicionais:

- Há no máximo um vínculo não revogado por conta, unidade e papel. Vínculo
  revogado ou rejeitado não impede novo convite para a mesma unidade e papel.
- Revogações que envolvem gestores são serializadas por condomínio; depois da
  trava, quem revoga precisa continuar gestor ativo. Assim a revogação cruzada
  entre dois gestores nunca deixa o condomínio sem gestor.
- Ninguém decide sobre o próprio vínculo; outro gestor precisa aprovar ou rejeitar.
- Unidade inativada depois da emissão bloqueia o aceite com a mesma resposta
  neutra.
- A validade do convite é calculada pelo relógio do banco.

Limites conhecidos: o aceite publica `app.tenant_id` para quem ainda não é membro,
apenas depois de travar um convite válido e somente para as instruções fixas do
aceite (endurecimento com função `SECURITY DEFINER` pendente). O claim `email` é
usado sem checar `email_verified`; a proteção depende de cadastro público
desligado e contas criadas pela operação. `email_hash` é SHA-256 sem chave secreta
(HMAC pendente). Convite de `CONCIERGE` aprovado ainda exige o cadastro
supervisionado do segundo fator descrito em OPERACAO_MFA.md antes de qualquer
operação de portaria. A lista de convites mostra os 500 mais recentes.

## Pedidos, decisões e bloqueios de reserva (V008)

| Rota | Capacidade | Observação |
| --- | --- | --- |
| `GET .../management/reservations?status=&from=&until=` | `reservations.decide` | Sem período: ainda não encerradas; com período: até 366 dias; até 200 itens. Pedido com prazo vencido aparece como `EXPIRED` |
| `POST .../reservations/{id}/decision` | `reservations.decide` | `APPROVE`/`REJECT` (motivo obrigatório na rejeição) pela versão lida. Repetição devolve o estado atual sem novo evento; decisão oposta recebe 409. Prazo vencido grava `EXPIRED` e responde 409 `RESERVATION_DECISION_EXPIRED`, inclusive com versão antiga ou quando o prazo vence durante a própria decisão. Quem pediu não decide o próprio pedido: 409 `RESERVATION_SELF_DECISION_DENIED` |
| `POST .../reservations/{id}/cancel` | `reservations.decide` (+ `amenities.manage` para bloqueio) | Motivo obrigatório; reserva confirmada, pedido pendente ou bloqueio ainda não encerrado; repetição sem novo evento. Pedido vencido é gravado como `EXPIRED` e o cancelamento recebe 409 `RESERVATION_DECISION_EXPIRED` |
| `POST .../amenities/{id}/maintenance-blocks` | `amenities.manage` | `Idempotency-Key`; período de até 366 dias a partir de agora; 409 `AMENITY_BLOCK_CONFLICT` enquanto houver pedido ou reserva válida no período |

Fluxo do morador: área com aprovação gera `PENDING_APPROVAL` com prazo de decisão
de até 24 horas, nunca depois do início (prazo fixo; configuração por condomínio
pendente). O pedido ocupa o período e conta na cota, mas não é confirmação. O
autor pode retirá-lo até o início; o prazo de cancelamento aceito continua valendo
para reservas confirmadas. Outra pessoa da unidade vê a reserva, mas recebe 403
`RESERVATION_AUTHOR_REQUIRED` ao tentar cancelá-la. Se a gestão já cancelou, o
pedido de cancelamento do autor recebe 409 `RESERVATION_CHANGED` (não é apresentado
como cancelamento próprio) e a agenda mostra o motivo da gestão. A criação expira, sob a trava da unidade, os pedidos
vencidos da própria unidade e os que ocupam o período pedido; pedido vencido não
aparece como ocupação nem como pendente (AC-013). A aprovação exige área ativa,
unidade ativa e solicitante ainda apto a reservar pela unidade. Toda decisão grava
auditoria (ator e motivo) e evento na mesma transação; o motivo aparece para a
unidade.

No banco (V008), políticas restritivas: confirmar ou rejeitar exige
`reservations.decide`; área que exige aprovação nunca recebe `BOOKING` já
`CONFIRMED` por `INSERT`; inserir ou alterar bloqueio exige `amenities.manage`;
`INSERT` pela aplicação só cria `BOOKING` `PENDING_APPROVAL`/`CONFIRMED` e `BLOCK`
`CONFIRMED` (sem `HELD` enquanto a fila de espera não existir); motivo com até 500
caracteres. Um gatilho `BEFORE UPDATE OF status`, válido para qualquer papel,
mantém a máquina de estados: `HELD` → `PENDING_APPROVAL`/`CONFIRMED`/`CANCELLED`/
`EXPIRED`; `PENDING_APPROVAL` → `CONFIRMED`/`REJECTED`/`CANCELLED`/`EXPIRED`;
`CONFIRMED` → `CANCELLED`. Estados encerrados não voltam (SQLSTATE 23514); correção
operacional exige migration revisada.

Limites conhecidos: a elegibilidade do solicitante na aprovação lê vínculos da
unidade; se a leitura não encontrar vínculo ativo (inclusive por falta de
visibilidade), a aprovação é recusada (falha fechada) e o pedido pode ser
rejeitado com motivo. Expiração automática não gera auditoria de ator, somente
evento `RESERVATION_EXPIRED`. Gestor único que também é morador não aprova o
próprio pedido: outro gestor decide ou o autor retira o pedido. A lista da gestão
devolve até 200 itens por consulta (`truncated` indica corte).

Pendências deste fluxo: revogar convites de visitantes vinculados na mesma
transação do cancelamento, da rejeição e da expiração (o módulo de visitantes ainda
não existe; o evento gravado junto serve a avisos e não substitui a revogação
transacional, por isso o aceite de CP-023 continua pendente); retenções `HELD` e fila de espera;
prazo de decisão configurável; worker de expiração (hoje a expiração acontece na
criação seguinte e na decisão tardia); notificações.

## Telas web (`/gestao/{condomínio}`)

O acesso aparece na escolha de contexto apenas para vínculo `MANAGER` vigente. A
porta da gestão consulta `GET /auth/mfa`: sem sessão AAL2 nenhuma rota de gestão é
chamada; a pessoa cadastra o autenticador (chave e QR exibidos uma única vez, só no
estado da tela) e verifica o código. A verificação troca a sessão e o CSRF. Uma
resposta `MFA_REQUIRED` posterior volta à verificação; `STAFF_ACCESS_DENIED` ou
`TENANT_ACCESS_DENIED` removem os dados da gestão da tela.

Seções: Pendências (pedidos de reserva e vínculos aguardando decisão), Reservas
(agenda de 30 dias, decisão, cancelamento com motivo e bloqueio de manutenção com
chave idempotente reaproveitada enquanto o formulário não muda), Áreas (cadastro e
edição de regras, com aviso de nova versão), Unidades (blocos e unidades, aviso ao
inativar), Pessoas (vínculos por situação, convite com link exibido uma vez e
revogação) e Auditoria (paginação por cursor). Ações nunca repetem sozinhas; falha
incerta pede para atualizar a lista antes de repetir.

Edições e decisões usam a versão lida ao abrir o formulário. Se a lista atualizada
trouxer versão mais nova, a tela avisa e oferece recarregar os dados atuais em vez
de enviar sobre o que outra pessoa mudou; `409` mantém o formulário e pede revisão.
Repetição natural do servidor (decisão, cancelamento ou bloqueio já feitos) aparece
como informação ou aviso com o estado atual, nunca como sucesso desta ação. Conta só
de gestão (sem unidade) vê apenas o acesso à gestão na escolha de contexto.

O QR do autenticador vem do GoTrue como SVG cru; o BFF aceita SVG cru ou em data URI
e o devolve em `data:image/svg+xml;base64,` (até 512 KiB de SVG). Fora disso o QR é
omitido e a chave continua disponível para cadastro manual. O formato real do
provedor ainda não foi conferido em homologação de Auth.

O convite é aberto em `/convite#token=…`: o token fica no fragmento (não vai ao
servidor nem aos registros de acesso), é guardado só na aba atual e removido da
barra de endereço. Quem já entrou aceita com um clique; a tela de acessos mostra o
convite em aberto. Quem ainda não entrou vê o formulário de primeiro acesso (abaixo).
Sair da conta descarta o convite guardado na aba.

Primeiro acesso por convite (`POST /auth/invitation/accept`, atrás de
`CP_BFF_ENABLED` e `CP_MANAGEMENT_ENABLED`): o login comum do BFF exige conta local
ativa, então quem ainda não tem conta local envia e-mail, senha do provedor e token
do convite juntos. O BFF autentica no provedor, executa o aceite (espelho local da
conta e vínculo `PENDING` na mesma transação, com o e-mail do token) e só então cria
a sessão web, renovando o CSRF. Recusa do aceite encerra a sessão aberta no provedor
e não cria sessão local; se o aceite for gravado mas a sessão não, a resposta indica
`sessionStarted=false` e a tela orienta o login comum. Mesmo limite de tentativas do
login, Origin e CSRF; a web guarda a espera do `429` para as duas rotas. O provedor
recebe o e-mail na mesma forma contada pelo limite (sem espaços nas pontas, em
minúsculas). Sessão anterior no navegador é substituída pela nova ou, sem sessão nova,
removida; falha ao encerrá-la no servidor fica no log e não desfaz o aceite. Erro de
senha, conta inativa e espera ficam no formulário; resultado incerto (rede ou `5xx`)
é estado final que orienta o login comum, sem reenviar o mesmo convite. Aceites
simultâneos da mesma conta nova reutilizam o espelho local. Login comum recusado por
conta inexistente aponta para o convite aberto na aba.

## Pendências

V006–V008 ainda não foram aplicadas
ao banco remoto: o executor de migrations está fixado em V003–V005 e exige
revisão e autorização próprias. Envio automático do convite por e-mail depende de
SMTP e de credencial administrativa fora da API.

Primeiro acesso por convite: a conta precisa existir no Supabase Auth (cadastro
público desligado; criação pela operação) e o e-mail do token é usado sem conferir
`email_verified`, como no aceite autenticado — conferir no ensaio com Auth real as
opções de confirmação e troca segura de e-mail e a normalização do e-mail pelo
provedor. Resposta idempotente para convite já aceito pela mesma conta (hoje `404`
neutro) é melhoria registrada. Ensaio com Auth real pendente.
