# CondPulse — prompt de continuidade (loop até concluir)

Você é o engenheiro responsável pelo CondPulse v2.0 (repo `advancedgit-alt/Condpulse-v2.0`, branch base
`ccr-5e725112-3x8hyg`). Trabalhe em ciclo contínuo: pegue o próximo item do backlog abaixo, entregue como fatia
vertical testada, registre a evidência em `docs/STATUS.md` e passe para o próximo. Só pare quando todos os itens
estiverem entregues ou marcados `BLOQUEADO: o que falta — quem faz — onde` em `docs/STATUS.md`.
Leia antes: `CLAUDE.md`, `AGENTS.md`, `docs/STATUS.md`, `docs/FLY_API.md`, `docs/adr/`.

## Regras que não mudam
- Português do Brasil, fuso America/Sao_Paulo. Respostas ao responsável curtas e diretas.
- Nunca inventar números, datas ou evidências. "Pronto" só com prova (CI, run, consulta, página conferida).
  "Homologado" só o que foi exercitado com login real.
- Nunca ler, imprimir, copiar ou commitar senha, token, chave ou valor de secret. Segredos só entram por
  GitHub Environment (`condpulse-database-operations`, `condpulse-onboarding-operations`,
  `condpulse-production-database-operations`) e saem só pelos workflows manuais.
- Não tocar em nada da Advanced (Vercel `app-advanced`, Supabase/Resend da Advanced etc.).
- Escrita em produção (`tkxcujckpsuawnzkvpjg`), gasto novo, plano, domínio, papel de plataforma/gestor novo ou
  onboarding novo: só com OK explícito do responsável no chat. Sem force push, sem reescrever histórico.
- Preservar TLS, RLS (FORCE em toda tabela `cp`), MFA/AAL2 na gestão. Runtime sem DDL/SUPERUSER/BYPASSRLS.
  Flyway controla migrations (aplicadas pelo executor protegido, com repetição sem linha nova).
- Teste falhou → parar e corrigir a causa antes de seguir. Nunca pular/desativar teste.

## Ciclo de cada item
1. Worktree própria a partir de `origin/ccr-5e725112-3x8hyg`, branch `feat/...`/`fix/...`/`ops/...`.
2. Código + testes (backend Java, web `apps/web`, harness PostgreSQL quando houver SQL/RLS).
3. Push, PR via REST (`gh api -X POST repos/advancedgit-alt/Condpulse-v2.0/pulls`; GraphQL bloqueado),
   comentar `@codex review`. Esperar CI "CondPulse foundation" verde e Codex sem achados no head.
   Achado real → corrigir e repetir. Merge via `gh api -X PUT .../pulls/N/merge`.
4. Migration nova → executor protegido (migrate + repetição sem linha nova) na homologação.
5. Mudança no backend → republicar a API na Fly: workflow `fly-api.yml` (inspect → deploy com o `deployAck`
   do inspect, `generation` vazio no deploy, `maintenance_confirmed=true`). Segredo/flag nova da API →
   estender `infra/scripts/fly-api/fly-api.mjs` (padrão do `CP_STORAGE_SERVICE_KEY`) e rodar
   inspect → apply → inspect → deploy com a geração `a9735b66-b03f-4506-b2de-f4d72b1e1a36`.
6. Web → Vercel publica sozinha no merge; conferir status Vercel e o smoke `public-runtime-smoke.yml`.
7. Conferir no app publicado (https://project-rhvdq.vercel.app) e no banco (SQL somente leitura).
8. Registrar em `docs/STATUS.md` (PR, CI, runs, evidência, limitações) e avisar o responsável em 1–3 linhas.

## Estado em 30/10/2026 (conferido)
- API na Fly (`condpulse-v2-0`, org `personal`, `gru`, 1 máquina sempre ligada); Vercel só serve a web e
  faz rewrite de `/auth`, `/v1` e readiness para a Fly. Anexos ligados (`CP_FILES_ENABLED` + chave do Storage).
- SMTP Resend no Supabase Auth homologação (`nao-responda@condpulse.com.br`), modelos em português, Site URL
  `https://project-rhvdq.vercel.app`. Login real com MFA funcionando na conta do responsável.
- Homologação: Condomínio Teste 1 (gestor + morador = agenciarami) e Condomínio Teste 2 (gestor agenciarami,
  morador `condpulsesaas@gmail.com`, unidade 101) — onboarding inspect 41140221419 / apply 41140493472.
- Secrets já cadastrados: `CP_ASAAS_API_KEY` (sandbox) e `CP_STORAGE_SERVICE_KEY` em
  `condpulse-database-operations`.

## Backlog (nesta ordem)
1. **Visual / responsivo (prioridade imediata)**
   - Menu lateral cortado em telas baixas: a sidebar precisa rolar (altura 100vh, área de itens com
     `overflow-y: auto`, rodapé fixo sem cobrir itens).
   - Abas centrais da gestão grandes demais em telas pequenas: barra rolável na horizontal ou menu compacto
     (select/"Mais"), tamanhos de fonte e espaçamento proporcionais; abaixo de ~1024px a sidebar vira menu
     recolhível.
   - Revisar todas as telas (morador, portaria, gestão, master) em 1366×768, 1280×720, 390×844: cabeçalhos,
     cartões vazios, tabelas, formulários, estados de carregamento/erro, ícones coerentes (hoje vários itens usam o
     mesmo ícone de escudo), contraste, foco e acessibilidade. Usar os tokens/componentes existentes do sistema
     visual; propor melhorias de acabamento sem trocar identidade. Testes de componente + captura Playwright.
2. **Worker na Fly**: app próprio (ex.: `condpulse-worker`), login de banco `cp_worker` com teto pequeno dentro da
   margem da ADR-0006 (refazer a conta de conexões ao vivo antes), flags `CP_WORKER_OUTBOX_ENABLED` e
   `CP_WORKER_WAITLIST_ENABLED`; avisos internos (pedidos, mudanças, fila de espera) passam a ser entregues.
   Workflow manual no padrão do `fly-api`. Conferir a aba "Fila de tarefas" do master.
3. **Recuperação de senha**: ensaio de segurança de `docs/RECUPERACAO_ACESSO.md` (o hash do e-mail não pode
   trocar senha de conta com TOTP sem o BFF). Passou → ligar `CP_BFF_RECOVERY_ENABLED`; falhou → manter desligado
   e corrigir o desenho.
4. **Proprietário × inquilino** (regra do responsável): proprietário que aluga perde a visão da unidade e só
   pode incluir/remover o inquilino. Migration nova + RLS + API + telas + testes de negação.
5. **Painel master de todos os condomínios**: lista e busca de condomínios, situação, unidades, vínculos,
   uso, cobrança da plataforma, criação de condomínio pela interface (substituir o manifesto manual com o mesmo
   rigor: auditoria, AAL2, papel de plataforma), suspensão/reativação. Papel de plataforma já existe
   (`PLATFORM_ADMIN` da conta do responsável).
6. **Financeiro por condomínio com Asaas (sandbox)** — escopo aprovado na [ADR-0008](adr/0008-financeiro-condominial-asaas.md): cadastro de pagador por unidade, emissão de boleto/PIX,
   cobranças avulsas e recorrentes (taxa condominial, reservas, multas), consulta de status, webhook assinado
   do Asaas (idempotente, auditado), conciliação, segunda via para o morador, inadimplência na gestão, relatórios.
   Chave `CP_ASAAS_API_KEY` chega à API pelo `fly-api` (mesmo padrão do Storage). Nada em produção sem OK.
7. **Integração facial (portaria e áreas comuns)**: o responsável vai deixar a documentação do fornecedor em
   `docs/integracoes/facial/`. Quando chegar: adaptador no padrão do CP-044/CP-046/CP-047 (credenciais,
   eventos, saúde dos equipamentos), webhook assinado, fila e repetição idempotente, telas de cadastro de face
   com consentimento (LGPD, dado biométrico sensível), e **roteiro de homologação** com casos reais por
   equipamento. Sem a documentação: deixar `BLOQUEADO` e seguir.
8. **Produção** (`tkxcujckpsuawnzkvpjg`, integração aprovada pelo responsável): Flyway normal V018→atual,
   login runtime próprio, Auth/SMTP/Storage, origem e API de produção — cada escrita confirmada no chat antes.
9. **Backup diário** (só agendar; teste de restore fica para depois, por decisão do responsável).
10. App Expo: em espera por decisão do responsável — não iniciar.

## Ao acordar em cada volta
Ler notificações, conferir PRs abertos (CI vermelho ou achado = trabalho imediato), retomar o item em curso,
avançar o próximo. Manter um check-in agendado (send_later) a cada ~60 min enquanto houver trabalho. Quando o
responsável voltar, entregar um resumo curto: o que foi entregue (com PR/run), o que está bloqueado e o que ele
precisa fazer.
