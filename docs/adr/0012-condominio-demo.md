# ADR-0012 - Condomínio demo isolado com dados sintéticos (CPX-046)

Estado: proposta nesta entrega (PR #168, branch `feat/cpx-condominio-demo`), pendente de revisão; abordagem SECURITY DEFINER com dono `cp_demo` e extensão dos guards confirmada pela coordenação em 01/11/2026. Referências: PRD
complementar Segurança v1.0 (E10.4, CPX-046), ADR-0007 (tenancy), ADR-0011 (parceiro e Log de Segurança),
`db/migrations/V053__demo_tenant.sql`, [ROTEIRO_TESTES_HML.md](../ROTEIRO_TESTES_HML.md).

## Contexto

O responsável precisa testar todas as jornadas com login real e segundo fator antes de existir parceiro ou condomínio
piloto. Os dados não podem ser reais (AGENTS.md: fixtures sintéticas, nada de dado pessoal real) e não podem se misturar
com condomínios reais. Semear um condomínio completo (avisos, encomendas, ocorrências, parceiro, escala, Log de
Segurança) pela role de runtime exigiria afrouxar grants e políticas que o `DatabaseRoleGuard` protege (autoria,
horários e estados vêm do banco; a API não grava `created_by` de outra conta, por exemplo).

## Decisão

1. **Marca imutável.** `platform.tenants.demo`, `platform.partners.demo` e `platform.accounts.synthetic`, padrão
   `false`, fora dos grants das roles de aplicação e imutáveis por gatilho (nem o dono do schema muda). Nome do demo
   sempre com o prefixo `[DEMO] `; condomínio ou parceiro real nunca usa esse prefixo (CHECK).
2. **Isolamento por gatilho.** Conta sintética só tem vínculo em condomínio demo e papel em parceiro demo; contrato só
   demo × demo. Contas reais podem ser vinculadas ao demo, sempre por ação explícita (convite de gestor da central,
   convites da gestão, papel no parceiro pela central). Nenhuma conta real recebe papel na criação.
3. **Operação única da central.** `platform.demo_provision(expected_tenant, motivo, chave)`: papel `PLATFORM_ADMIN`
   vigente, capacidade `platform.demo.manage` (publicada pela API só com AAL2) e sem condomínio no contexto. Cria a
   geração nova e, se houver demo ativo, aposenta-o antes (condomínio suspenso, contrato encerrado, parceiro suspenso).
   Nada é apagado: logs e trilhas só acrescentam. Condomínio que não é o demo ativo é recusado (`CPD01` real/inexistente,
   `CPD02` demo desatualizado); chave repetida devolve a mesma geração (`CPD03` se o pedido mudou).
4. **Primeira função SECURITY DEFINER do projeto, com dono restrito.** A função (e as consultas de marca
   `tenant_is_demo`, `partner_is_demo`, `account_is_synthetic`) pertencem à role `cp_demo`: `NOLOGIN`, sem
   `SUPERUSER`/`BYPASSRLS`/`CREATEROLE`, sem membro de runtime e sem ser membro de runtime. `cp_demo` só lê e grava,
   por RLS própria, linhas de condomínio/parceiro demo e contas sintéticas (mais a própria conta do ator para conferir o
   papel). Os gatilhos de negócio continuam valendo dentro da função (autoria do Log de Segurança, cadeia, auditoria
   exigida). `SET CONSTRAINTS ALL IMMEDIATE` faz os gatilhos adiados rodarem dentro da função, com o ator de cada etapa.
   O `DatabaseRoleGuard` passa a recusar runtime membro de `cp_demo`, grant nas marcas e qualquer função SECURITY
   DEFINER em `cp`/`platform` cujo dono não seja `cp_demo`.
5. **Rastro.** Trilha da plataforma: `DEMO_TENANT_PROVISIONED`, `DEMO_TENANT_RETIRED` e os `PARTNER_*` exigidos pelos
   gatilhos da V049 (com `details.demo=true`). Log de Segurança do demo: `PARTNER_CONTRACT_CHANGED`, `SCHEDULE_ASSIGNED`,
   `ROLE_GRANTED` e eventos de exemplo; nenhum Log de Segurança real é tocado.
6. **Flag.** `CP_DEMO_ENABLED` (padrão `false`) além de `CP_PLATFORM_ENABLED`. A migration não cria demo: ele só nasce
   pela ação da central. O segundo projeto Supabase (produção) não recebe a V053 sem OK do responsável.

7. **Guards de dono de função.** Os cinco executores que conferem posse em `platform`/`cp` (migrate de homologação
   e de produção, `FlyRuntimeLogin`, `FlyWorkerLogin`, `RuntimeConfiguration`) exigiam dono `postgres` e recusavam
   qualquer `SECURITY DEFINER`. Passam a aceitar, **só para funções**, dono exatamente `cp_demo` quando a role cumpre os
   invariantes: NOLOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE, NOCREATEDB, NOREPLICATION, NOINHERIT; `cp_demo` não é
   membro de ninguém; e todo vínculo em `cp_demo` (`pg_auth_members`) tem `inherit_option=false` e `set_option=false`
   (`admin_option` livre: é o vínculo que quem cria a role com CREATEROLE recebe, caso do `postgres` do Supabase) e
   o membro não é `cp_api`, `cp_worker`, `cp_onboarding` nem `cp_runtime_*`/`cp_worker_*`/`cp_onboarding_*`. Qualquer
   outro dono, `SECURITY DEFINER` fora de `cp_demo` ou `cp_demo` fora dos invariantes continua bloqueando. Tabelas,
   sequências e schemas seguem exigindo `postgres`. Coberto no cenário `owners` dos harnesses `DatabaseOperationsHarness`
   e `ProductionOperationsHarness` (aceite com `cp_demo` íntegra e com vínculo só de administração; recusa com LOGIN,
   como membro, com membro INHERIT ou SET, com membro de runtime, função de outro dono e definer fora de `cp_demo`).
8. **Quem migra sem ser superusuário (postgres do Supabase).** `postgres` não é superusuário ali: tem CREATEROLE e
   BYPASSRLS, e todo role que cria recebe vínculo `ADMIN OPTION` (INHERIT FALSE, SET FALSE, grantor `supabase_admin`).
   A V053: (a) revoga de PUBLIC e concede EXECUTE **antes** de transferir a posse (depois, sem INHERIT, GRANT/REVOKE
   de quem migrou viram "no privileges were granted" silenciosos); (b) concede SET a si mesma só durante a
   transferência e, no fim, revoga **apenas a opção SET** do vínculo que concedeu (`REVOKE SET OPTION FOR ... GRANTED
   BY`), removendo o vínculo só se não sobrar nenhuma opção; o ADMIN original fica. Superusuário (CI, local) já tem
   SET e nada é concedido nem revogado. O bloco termina conferindo o invariante do item 7 e que quem migrou continua
   com ADMIN (ou é superusuário). Coberto em `tests/database/run.py` (`demo_nonsuperuser_checks`: V001–V053 aplicadas
   por role CREATEROLE+BYPASSRLS sem superusuário).
   **Recuperação / manutenção futura da função** (quem migrou, com ADMIN em `cp_demo`): `GRANT cp_demo TO <eu> WITH SET
   TRUE; GRANT CREATE ON SCHEMA platform TO cp_demo; SET ROLE cp_demo; CREATE OR REPLACE FUNCTION ...; RESET ROLE;
   REVOKE CREATE ON SCHEMA platform FROM cp_demo; REVOKE SET OPTION FOR cp_demo FROM <eu> GRANTED BY <eu>; REVOKE
   cp_demo FROM <eu> GRANTED BY <eu>` — exercitado no mesmo teste. Uma V05x futura que precise alterar a função deve
   seguir esse padrão (conceder SET, agir, revogar só a opção SET).

## Consequências

- A transferência de posse exige que quem roda a migration possa assumir `cp_demo` (item 8). O caminho sem superusuário
  é exercitado no harness; na homologação ainda não foi aplicado.
- Gerações aposentadas acumulam (condomínio suspenso, parceiro suspenso). Sem expurgo, como o Log de Segurança.
- Reset tira o acesso das contas reais vinculadas à geração anterior; os vínculos precisam ser refeitos.
- Contas sintéticas não têm usuário no Auth: ninguém entra com elas.
