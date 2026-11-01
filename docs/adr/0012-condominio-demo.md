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
   e de produção, `FlyRuntimeLogin`, `FlyWorkerLogin`, `RuntimeConfiguration`) exigiam dono `postgres` (os três últimos
   recusavam também qualquer `SECURITY DEFINER`). Passam a aceitar, **só para funções**, dono exatamente `cp_demo` quando
   a role cumpre os invariantes: NOLOGIN, NOSUPERUSER, NOBYPASSRLS, NOCREATEROLE, NOCREATEDB, NOREPLICATION, NOINHERIT,
   sem membros e sem ser membro de ninguém. Qualquer outro dono, `SECURITY DEFINER` fora de `cp_demo` ou `cp_demo` fora
   dos invariantes continua bloqueando. Tabelas, sequências e schemas seguem exigindo `postgres`. Coberto no cenário
   `owners` dos harnesses `DatabaseOperationsHarness` e `ProductionOperationsHarness` (aceite com `cp_demo` íntegra;
   recusa com LOGIN, com membro, como membro, função de outro dono e definer fora de `cp_demo`).

## Consequências

- A transferência de posse exige que quem roda a migration possa assumir `cp_demo` (PostgreSQL 16+: `GRANT ... WITH SET
  TRUE` temporário, revogado no fim, e `CREATE` no schema só durante a transferência). Em CI roda como superusuário;
  na homologação (Supabase, `postgres` com `CREATEROLE`) o caminho é o mesmo, ainda não exercitado remotamente.
- Gerações aposentadas acumulam (condomínio suspenso, parceiro suspenso). Sem expurgo, como o Log de Segurança.
- Reset tira o acesso das contas reais vinculadas à geração anterior; os vínculos precisam ser refeitos.
- Contas sintéticas não têm usuário no Auth: ninguém entra com elas.
