# CondPulse
## PRD Complementar — Módulo Segurança, Acesso e Portaria (v1.0)

Data: 31/10/2026 · Complementa: `spec/documentacao/CondPulse_PRD_Arquitetura_v1.0.md` (PRD v1.0) e `CondPulse_Acesso_Facial_v1.1` · Base lida: repositório `Condpulse-v2.0`, HEAD `a8854ec` (migrations V001–V047).

> **Como usar este documento.** Ele descreve o que será construído **depois** dos tickets atuais do PRD v1.0 (CP-001 a CP-068). Nada aqui altera regra já fechada do PRD v1.0, exceto onde a seção 3 diz explicitamente "substitui". Os tickets deste documento usam o prefixo **CPX-** para não colidir com os CP- existentes. Agentes de IA devem seguir `AGENTS.md` e `CLAUDE.md` do repositório: testes antes de avançar, RLS em toda tabela de negócio, nada de mock em produção.

---

# 1. Resumo executivo e decisões fechadas

O CondPulse é o **sistema de segurança, acesso e portaria** do condomínio. Ele é vendido para **empresas parceiras** (segurança patrimonial, portaria remota, portaria presencial terceirizada e integradoras de tecnologia) e usado no dia a dia por **porteiros, operadores remotos e moradores**.

Decisões fechadas:

1. **Nada de síndico.** Funções de síndico (assembleias, contratos, cotações, infrações, NPS de síndico, cockpit de sindicatura, rastreio GPS de funcionários) ficam no **App Advanced**. O CondPulse integra com o Advanced e não replica essas funções.
2. **Administradora é outro módulo.** Financeiro condominial (taxa, boleto, PIX, inadimplência) pertence ao futuro **Módulo Administradora**, que vai concorrer com o Ahreas. O código existente (V036, Asaas) fica **congelado** (não é apagado nem evoluído) até a decisão final (seção 22).
3. **Cada tipo de usuário tem o seu painel.** Após o login em `/entrada`, a pessoa cai direto no painel da sua função. Quem tem mais de uma função escolhe o contexto. Nenhum painel mostra o que a função não pode fazer.
4. **Existe um gestor líder da segurança.** É o papel que acompanha todos os processos de segurança da carteira de condomínios do parceiro, audita o log, revisa aberturas remotas e responde por incidentes.
5. **Tudo que é de segurança fica registrado** no **Log de Segurança**: imutável (só acrescenta), encadeado por hash, com relógio do banco, autor, motivo e evidência. Correção é sempre um novo evento.
6. **IA não decide acesso nem segurança** (mantém PRD v1.0 cap. 1 e Facial v1.1 §2).
7. **Do Lovable aproveitam-se ideias e fluxos, não código** (stack diferente e falhas de segurança: sessão do morador em `localStorage`, WhatsApp como autenticação, IA decidindo acesso).

---

# 2. Públicos e posicionamento

| Produto | Para quem | Papel |
| --- | --- | --- |
| App Advanced | Síndico | Gestão do síndico. Fora do CondPulse |
| **CondPulse – Segurança e Acesso** (este PRD) | Empresas de segurança, portaria remota e presencial, integradoras, moradores | Entrada e saída, abertura e fechamento, checagens da portaria, permanência, reservas, encomendas, histórico de quem entrou e saiu, log de segurança |
| CondPulse – Administradora (futuro) | Administradoras | Módulo separado, a desenhar, concorrente do Ahreas |

Cliente pagante principal: **o parceiro** (empresa de segurança, portaria ou integradora), que opera vários condomínios. O condomínio também pode contratar direto (sem parceiro), caso em que a plataforma faz o papel do parceiro.

---

# 3. Hierarquia de contas (substitui parte do PRD v1.0 cap. 8)

Hoje o v2.0 tem dois níveis: **plataforma** (`platform.platform_roles`, papel `PLATFORM_ADMIN`) e **condomínio** (`cp.memberships`, papéis `RESIDENT`, `RESPONSIBLE`, `DEPENDENT`, `OWNER`, `CONCIERGE`, `MANAGER`, `SUPPORT`, `MAINTENANCE` — V046). Este PRD acrescenta o nível **Parceiro**:

```
Plataforma (Master)
  └── Parceiro (empresa de segurança / portaria / integradora)
        ├── Equipe do parceiro (gestor, líder de segurança, supervisor, operador remoto, técnico)
        └── Condomínios da carteira (contrato parceiro × condomínio)
              ├── Equipe local (porteiro, gestão local, zeladoria)
              └── Unidades → moradores, dependentes, proprietários, inquilinos
```

Regras:

- **R3.1** O isolamento continua por condomínio (`tenant_id` + RLS). A equipe do parceiro **não** ganha acesso a um condomínio só por ser do parceiro: precisa de (a) contrato ativo parceiro × condomínio, (b) papel ativo no parceiro e (c) para operadores e supervisores, **escala vigente** naquele condomínio.
- **R3.2** O gestor líder de segurança e o gestor do parceiro acessam todos os condomínios **com contrato ativo** da carteira, sem escala, e todo acesso a dado pessoal fica no Log de Segurança.
- **R3.3** Encerrar o contrato parceiro × condomínio revoga imediatamente o acesso de toda a equipe do parceiro àquele condomínio e gera evento no log.
- **R3.4** Um condomínio tem no máximo **um** parceiro operador de segurança ativo por vez (evita duas centrais abrindo o mesmo portão). Troca de parceiro é uma operação auditada com período de transição.
- **R3.5** Uma conta pode ter papéis em vários parceiros e condomínios; o contexto ativo é escolhido na `/entrada` e vale para a sessão.

---

# 4. Papéis e painéis por função

Cada papel tem **um painel próprio**, com rota própria, menu próprio e só as ações que a função permite. A rota base segue `apps/web/src/ui/shell/paths.ts` (`/c/{condomínio}/...` e `/central/*`) e acrescenta `/p/{parceiro}/...` para o nível parceiro.

## 4.1. Mapa de papéis

| # | Papel (código proposto) | Nível | Quem é | Painel | Rota | Existe no v2.0? |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Master da plataforma (`PLATFORM_ADMIN`) | Plataforma | Equipe CondPulse | Central da Plataforma | `/central` | Sim |
| 2 | Suporte CondPulse (`SUPPORT`) | Plataforma | Suporte técnico | Painel de Suporte | `/central/suporte` | Papel sim, painel não |
| 3 | Gestor do parceiro (`PARTNER_ADMIN`) | Parceiro | Dono ou diretor da empresa | Painel do Parceiro | `/p/{parceiro}/gestao` | **Novo** |
| 4 | **Gestor líder de segurança** (`SECURITY_LEAD`) | Parceiro | Coordenador / gerente de segurança | **Comando de Segurança** | `/p/{parceiro}/comando` | **Novo** |
| 5 | Supervisor de operação (`SECURITY_SUPERVISOR`) | Parceiro | Líder de turno da central | Painel do Turno | `/p/{parceiro}/turno` | **Novo** |
| 6 | Operador de portaria remota (`REMOTE_OPERATOR`) | Parceiro | Atendente da central | Console Remoto | `/p/{parceiro}/console` | **Novo** |
| 7 | Técnico / integrador (`TECHNICIAN`) | Parceiro | Instala e mantém equipamentos | Painel Técnico | `/p/{parceiro}/tecnico` | Parcial (concessão técnica V037, implantação) |
| 8 | Porteiro presencial (`CONCIERGE`) | Condomínio | Funcionário na guarita | Painel da Portaria | `/c/{cond}/portaria` | Sim |
| 9 | Gestão local (`MANAGER`) | Condomínio | Responsável do prédio (não é função de síndico) | Painel do Condomínio | `/c/{cond}/gestao` | Sim |
| 10 | Zeladoria (`MAINTENANCE`) | Condomínio | Zelador | Painel da Zeladoria | `/c/{cond}/zeladoria` | Papel sim, tela "em breve" |
| 11 | Morador responsável (`RESPONSIBLE`) | Unidade | Titular da unidade | App do Morador (completo) | `/c/{cond}/morador` + mobile | Sim |
| 12 | Morador (`RESIDENT`) | Unidade | Mora na unidade | App do Morador | idem | Sim |
| 13 | Dependente (`DEPENDENT`) | Unidade | Familiar com permissões dadas | App do Morador (restrito) | idem | Sim |
| 14 | Proprietário não residente (`OWNER`) | Unidade | Dono que não mora | App do Morador (consulta) | idem | Sim |
| 15 | Visitante / prestador | Sem conta | Quem recebe convite | Página do convite (link/QR) | `/convite/{token}` | Sim (`permit_links`) |

## 4.2. Entrada única e escolha de contexto

- **R4.1** O login (BFF com cookie opaco e MFA, já existente) leva sempre a `/entrada`.
- **R4.2** Com um único papel, a pessoa vai direto ao seu painel. Com mais de um, vê cartões "Parceiro X – Comando de Segurança", "Condomínio Y – Portaria" etc.
- **R4.3** Papéis do parceiro e da portaria exigem **MFA (AAL2)**, como já exige `concierge.operate` hoje.
- **R4.4** Trocar de contexto encerra a visão anterior e grava `CONTEXT_SWITCHED` no Log de Segurança para papéis de segurança.
- **R4.5** O menu é gerado pelas **capacidades** do papel (seção 4.4), nunca por esconder botões no frontend: a API e o RLS recusam o que o papel não tem.

## 4.3. Painéis — conteúdo, ações e limites

### 4.3.1. Central da Plataforma (`PLATFORM_ADMIN`)
- **Vê:** parceiros, condomínios, contratos, módulos (V045), cobrança da assinatura, concessões técnicas (V037), homologações do facial, saúde da plataforma, outbox.
- **Faz:** cria e suspende parceiros e condomínios; vincula condomínio a parceiro; libera módulos; gerencia chaves e tokens do facial; libera pagamentos.
- **Não faz:** ver conteúdo pessoal dos condomínios sem concessão temporária auditada; abrir portão.

### 4.3.2. Painel de Suporte (`SUPPORT`)
- **Vê:** metadados técnicos (erros, filas, saúde de equipamentos, versões).
- **Faz:** pede concessão temporária de acesso a um condomínio, com motivo e prazo, aprovada pelo parceiro ou pela gestão local.
- **Não faz:** ver dados pessoais sem concessão; operar portaria.

### 4.3.3. Painel do Parceiro (`PARTNER_ADMIN`)
- **Vê:** carteira de condomínios (status, contrato, módulos, equipamentos online), equipe do parceiro, faturas da assinatura, indicadores consolidados.
- **Faz:** convida e desliga equipe; atribui papéis; cadastra condomínios (com o assistente de implantação, CPX-E10); configura white-label do parceiro; define padrões que os condomínios herdam (tempos de permanência, níveis de alerta, SLA).
- **Não faz:** abrir portão (a menos que também tenha papel operacional); apagar log.

### 4.3.4. **Comando de Segurança (`SECURITY_LEAD`) — gestor líder de segurança**

Objetivo: **acompanhar todos os processos de segurança** da carteira, garantir que nada fique sem registro e responder por incidentes.

| Área do painel | O que mostra | Ações |
| --- | --- | --- |
| Visão geral da carteira | Mapa/lista de condomínios com semáforo: equipamentos offline, portas abertas, permanências vencidas, alertas críticos abertos, central sem operador escalado | Abrir o condomínio em modo leitura; acionar supervisor |
| Alertas críticos ao vivo | Coação, emergência, arrombamento, pessoa restrita reconhecida, abertura remota fora do padrão | Assumir o incidente, designar responsável, registrar desfecho |
| Incidentes | Lista de incidentes com linha do tempo montada a partir do Log de Segurança | Abrir, anexar evidência, encerrar com relatório; exportar dossiê com manifesto de hash |
| Revisão de aberturas remotas | Toda abertura remota com operador, motivo, ponto, evidência e tempo | Marcar como "conferida" ou "irregular" (gera evento); filtro por operador e condomínio |
| Permanências | Pessoas no condomínio além do previsto, por condomínio e tipo | Cobrar a portaria / supervisor; justificar ou encerrar com motivo |
| Turnos | Passagens de turno com e sem aceite, checklists incompletos, rondas perdidas | Reabrir pendência; cobrar supervisor |
| Desempenho da operação | SLA da central por operador e condomínio, volume de eventos, tempo até atender e resolver | Exportar relatório |
| Lista de bloqueio | Restrições gerais e por unidade, com validade e base legal | **Aprovar** restrição geral (dupla aprovação com gestão local ou gestor do parceiro) |
| Log de Segurança | Busca completa no log da carteira (seção 5) | Filtrar, exportar com manifesto, verificar integridade da cadeia |
| Políticas de segurança | Padrões do parceiro: tempos, níveis de alerta, dupla confirmação, eclusa | Propor e publicar (com versão) |

Não faz: apagar ou editar evento do log; ver biometria (só referência); acessar condomínio sem contrato ativo.

### 4.3.5. Painel do Turno (`SECURITY_SUPERVISOR`)
- **Vê:** fila ao vivo da central, operadores logados, condomínios por operador, SLA em tempo real, alertas escalados.
- **Faz:** redistribui condomínios entre operadores; assume eventos; escala para o líder; abre e fecha o turno da central; confere passagem de turno da central.
- **Não faz:** alterar políticas publicadas; exportar log completo (só do seu turno).

### 4.3.6. Console Remoto (`REMOTE_OPERATOR`)
- **Vê:** fila única dos condomínios em que está escalado: chamadas de interfone, pedidos de abertura, chegadas sem convite, alertas de permanência, porta aberta, equipamento offline. Ao abrir um evento, vê o contexto do condomínio (unidades, autorizados, regras, contatos, último acesso, imagem do ponto quando houver).
- **Faz:** atende; liga para a unidade; abre/fecha remotamente com **motivo obrigatório** e, em pontos críticos, **dupla confirmação**; registra entrada/saída manual; registra ocorrência; encerra evento.
- **Não faz:** ver condomínio fora da escala; exportar dados; alterar regras.

### 4.3.7. Painel Técnico (`TECHNICIAN`)
- **Vê:** equipamentos da carteira, saúde (`cp.access_device_health`), firmware, comissionamento, publicações de direitos (V047).
- **Faz:** instala e comissiona com concessão técnica (V037) com prazo; testa abertura em modo manutenção (registrado).
- **Não faz:** ver cadastro de moradores além do necessário ao comissionamento; liberar acesso de pessoas.

### 4.3.8. Painel da Portaria (`CONCIERGE`)
- **Vê:** "quem está no condomínio agora", chegadas e convites do dia, encomendas, ocorrências, equipamentos do prédio, avisos da central.
- **Faz:** registra entrada/saída (V014), recebe e entrega encomenda, trata chegada sem convite (`arrival_requests`), abre ponto local, registra ocorrência, faz checklist e ronda por QR, passagem de turno com aceite.
- **Não faz:** exportar biometria, consultar outros condomínios, alterar regras (mantém PRD v1.0 cap. 8).

### 4.3.9. Painel do Condomínio (`MANAGER`)
- **Vê:** regras do prédio, pessoas e vínculos, reservas, pedidos, avisos, mapa de acesso, histórico de segurança **do próprio condomínio**.
- **Faz:** aprova vínculos e reservas, configura regras (tempos, áreas, prestadores), publica avisos, trata exceções, co-aprova restrição geral.
- **Não faz:** funções de síndico (fora do produto); abrir portão remoto; mexer em equipamento.

### 4.3.10. Painel da Zeladoria (`MAINTENANCE`)
- **Vê e faz:** pedidos e tarefas atribuídos (já previsto em V046), checklist da zeladoria.
- **Não faz:** ver acessos de pessoas.

### 4.3.11. App do Morador (`RESPONSIBLE`, `RESIDENT`, `DEPENDENT`, `OWNER`)
- **Início** com 4 atalhos (PRD v1.0 cap. 7): Convidar, Encomendas, Reservar, Falar com a portaria.
- **Faz:** convida em 3 toques e compartilha link; vê histórico de quem entrou e saiu da unidade; confirma saída do prestador; libera abertura pelo app (quando ligado para ele); pré-autoriza delivery; ativa modo viagem; cadastra pets e veículos; reserva áreas; recebe avisos; abre pedidos; aciona alerta de emergência; consulta documentos do condomínio.
- **Diferenças:** dependente só com permissões dadas pelo responsável; proprietário não residente só consulta o que a política permitir (não monitora a rotina do inquilino — PRD v1.0 cap. 8).

## 4.4. Capacidades (permissões)

O v2.0 já usa capacidades por condomínio (`platform.has_capability(...)`: `concierge.operate`, `audit.read`, `policy.edit`, `policy.publish`, `area.manage`, `memberships.manage` etc.). Este PRD acrescenta capacidades de **parceiro** e de **segurança**:

| Capacidade nova | Papéis |
| --- | --- |
| `partner.manage` | PARTNER_ADMIN |
| `partner.team.manage` | PARTNER_ADMIN |
| `security.command` (visão da carteira, incidentes) | SECURITY_LEAD |
| `security.log.read` (log completo da carteira) | SECURITY_LEAD; PARTNER_ADMIN (leitura) |
| `security.log.read.shift` (log do próprio turno) | SECURITY_SUPERVISOR |
| `security.log.export` | SECURITY_LEAD |
| `security.remote_open.review` | SECURITY_LEAD |
| `security.policy.publish` | SECURITY_LEAD |
| `security.blocklist.approve` | SECURITY_LEAD, MANAGER (co-aprovação) |
| `ops.shift.supervise` | SECURITY_SUPERVISOR |
| `ops.remote.operate` | REMOTE_OPERATOR, SECURITY_SUPERVISOR |
| `ops.remote.open` | REMOTE_OPERATOR, SECURITY_SUPERVISOR |
| `devices.commission` | TECHNICIAN (sempre com concessão técnica com prazo) |
| `security.log.read.tenant` (log do próprio condomínio) | MANAGER |

---

# 5. Log de Segurança

**Tudo que acontece na segurança do condomínio fica registrado, não pode ser apagado e pode ser provado.**

## 5.1. Relação com o que já existe

| Tabela existente | O que guarda hoje | Fica como está? |
| --- | --- | --- |
| `cp.audit_log` (V001) | Ações administrativas por recurso | Sim |
| `platform.platform_audit_log` (V010) | Ações da plataforma, append-only | Sim |
| `cp.access_events` (V014) | Entrada/saída manual da portaria, correção como novo evento | Sim |
| `cp.access_device_health` (V028) | Estado atual do equipamento | Sim |

O **Log de Segurança** (`cp.security_log`, nome proposto) é a **trilha única de segurança**: recebe, por gatilho ou pela camada de aplicação na mesma transação, os eventos de segurança das tabelas acima e dos módulos novos. As tabelas de domínio continuam sendo a fonte da regra; o log é a fonte da **prova**.

## 5.2. O que entra no log (catálogo inicial)

| Grupo | Eventos (`event_type`) | Severidade padrão |
| --- | --- | --- |
| Acesso de pessoas | `ENTRY_RECORDED`, `EXIT_RECORDED`, `ENTRY_DENIED`, `EVENT_CORRECTED`, `FACIAL_MATCH`, `FACIAL_NO_MATCH`, `QR_USED`, `QR_REJECTED` | Info / Atenção (negado) |
| Abertura e fechamento | `REMOTE_OPEN`, `LOCAL_OPEN`, `APP_OPEN` (morador), `DOUBLE_CONFIRM_GIVEN`, `INTERLOCK_BLOCKED`, `DOOR_HELD_OPEN`, `FORCED_OPEN` | Info / Crítico (forçado) |
| Permanência | `STAY_OPENED`, `STAY_DUE`, `STAY_OVERDUE_L1`, `STAY_OVERDUE_L2`, `STAY_CLOSED`, `STAY_JUSTIFIED` | Info / Atenção |
| Emergência | `PANIC_TRIGGERED`, `DURESS_CODE_USED`, `INCIDENT_OPENED`, `INCIDENT_ASSIGNED`, `INCIDENT_CLOSED` | Crítico |
| Lista de bloqueio | `BLOCKLIST_ADDED`, `BLOCKLIST_APPROVED`, `BLOCKLIST_REMOVED`, `BLOCKLIST_HIT` | Atenção / Crítico (hit) |
| Operação | `SHIFT_STARTED`, `SHIFT_HANDOVER_SUBMITTED`, `SHIFT_HANDOVER_ACCEPTED`, `CHECKLIST_DONE`, `PATROL_CHECKPOINT`, `PATROL_MISSED`, `QUEUE_ASSIGNED`, `SLA_BREACHED` | Info / Atenção |
| Equipamentos | `DEVICE_OFFLINE`, `DEVICE_ONLINE`, `DEVICE_COMMAND_SENT`, `DEVICE_COMMAND_FAILED`, `MAINTENANCE_MODE_ON/OFF`, `CREDENTIAL_PUBLISHED`, `CREDENTIAL_REVOKED` | Info / Atenção |
| Pessoas e sessões | `LOGIN_SUCCEEDED` (papéis de segurança), `LOGIN_FAILED`, `MFA_CHALLENGED`, `CONTEXT_SWITCHED`, `ROLE_GRANTED`, `ROLE_REVOKED`, `SCHEDULE_ASSIGNED` | Info |
| Configuração | `POLICY_PUBLISHED`, `RULE_CHANGED`, `TECH_GRANT_ISSUED`, `PARTNER_CONTRACT_CHANGED` | Info |
| Consulta sensível | `SENSITIVE_VIEWED` (ficha de pessoa, histórico de unidade por equipe), `LOG_EXPORTED`, `EVIDENCE_EXPORTED` | Info |
| Revisão | `REMOTE_OPEN_REVIEWED` (conferida/irregular) | Info / Atenção |

## 5.3. Campos de cada evento

| Campo | Descrição |
| --- | --- |
| `id` | uuid |
| `tenant_id` | Condomínio (obrigatório; eventos só de parceiro usam `partner_security_log`, mesma estrutura) |
| `partner_id` | Parceiro operador no momento (nulo se condomínio sem parceiro) |
| `seq` | Sequência por condomínio, sem buracos |
| `occurred_at` | Quando aconteceu (relógio do banco; para equipamento, horário do equipamento + `clock_skew_ms`) |
| `recorded_at` | Quando o servidor gravou (`clock_timestamp()`) |
| `event_type`, `severity` | Do catálogo (5.2) e `INFO`/`ATTENTION`/`CRITICAL` |
| `source` | `CONCIERGE`, `REMOTE_CENTRAL`, `RESIDENT_APP`, `FACIAL`, `QR`, `DEVICE`, `SYSTEM`, `PLATFORM` |
| `actor_account_id`, `actor_role`, `session_id` | Quem fez, em qual papel e sessão (nulo para equipamento/sistema) |
| `subject_type`, `subject_id` | Pessoa, unidade, convite, permanência, incidente… |
| `unit_id`, `point_id`, `device_id` | Onde |
| `reason` | Motivo (obrigatório em abertura remota, correção, justificativa, remoção de bloqueio, exportação) |
| `evidence_ref` | Referência a evidência (foto do ponto, gravação, anexo) — nunca o template biométrico |
| `correlation_id`, `trace_id` | Liga eventos do mesmo caso e à observabilidade |
| `corrects_id` | Quando o evento corrige outro |
| `details` | jsonb limitado (≤ 4 KB), sem dado biométrico |
| `prev_hash`, `hash` | Cadeia: `hash = sha256(prev_hash ‖ campos canônicos)` por condomínio |

## 5.4. Regras do log

- **R5.1 Só acrescenta.** A role de runtime tem só `INSERT` (e por coluna, sem colunas preenchidas pelo banco), nunca `UPDATE`/`DELETE`/`TRUNCATE`. O `DatabaseRoleGuard` já usado em V014 passa a checar também esta tabela.
- **R5.2 Correção é novo evento** com `corrects_id` e motivo, como em `cp.access_events`.
- **R5.3 Cadeia de hash** por condomínio, calculada no banco sob trava; um job diário grava a **âncora** (último hash do dia) em `platform` e o painel do líder mostra "cadeia íntegra / quebrada".
- **R5.4 Mesma transação.** O evento de domínio e o evento do log são gravados na mesma transação; se o log falhar, a operação falha.
- **R5.5 Equipamento offline:** eventos chegam depois pelo gateway com `occurred_at` do equipamento e `recorded_at` do servidor; o log nunca reordena, mostra os dois horários.
- **R5.6 Quem lê:** líder (carteira inteira), supervisor (seu turno), gestão local (seu condomínio, sem dados da equipe do parceiro além do papel), morador (apenas eventos da própria unidade, via histórico já existente). **Toda leitura de log por equipe gera `SENSITIVE_VIEWED`.**
- **R5.7 Exportação** só pelo líder, com motivo, gera arquivo + **manifesto** (intervalo de `seq`, hashes de início/fim, quem exportou) e evento `LOG_EXPORTED`.
- **R5.8 Retenção:** padrão a definir com o responsável por privacidade (PRD v1.0 cap. 24). Eventos ligados a incidente aberto ficam retidos até o encerramento.
- **R5.9 Biometria nunca entra no log** (Facial v1.1): só identificador do sujeito e resultado.
- **R5.10 Linguagem:** "sem registro de saída" não significa que a pessoa continua no condomínio (regra de `REGISTRO_ACESSOS.md`). O painel "quem está agora" mostra **permanências abertas**, com a origem de cada dado.

---

# 6. Base atual (Condpulse-v2.0, HEAD `a8854ec`)

Nenhum dos 68 tickets do PRD v1.0 foi aceito; login real com MFA não foi exercitado; facial só com adaptador simulado; app Expo sem build.

| Área | O que existe | Situação |
| --- | --- | --- |
| Login e vínculo | BFF com cookie, MFA, `/entrada`, troca de condomínio | Código pronto, sem login real testado |
| Reservas | Aprovação, fila de espera, convidados | Em implementação |
| Visitas | Convite por link (`permit_links`), prestador recorrente (`visit_rules`), chegada sem convite | Em implementação |
| Registro manual de entrada/saída | `cp.access_events` (V014) com correção | Em implementação |
| Encomendas | Recebimento, entrega, retirada delegada | Em implementação |
| Portaria | Chegadas, ocorrências (`concierge_occurrences`, V033) | Em implementação |
| Acesso físico | Gateway 4 marcas, mapa, políticas, grants, credenciais, saúde, publicações (V026–V047) | Adaptador simulado |
| Morador | Avisos, pedidos, mudanças, inquilino, caixa, histórico da unidade | Em implementação |
| Central | Condomínios, módulos, cobrança manual, concessões técnicas, facial | Em implementação |
| Auditoria | `cp.audit_log`, `platform.platform_audit_log` | Existe; sem log de segurança unificado |
| White-label | Validação no frontend | Sem backend |
| Financeiro condominial | V036 Asaas | **Congelado** (seção 1) |

---

# 7. Épicos

| ID | Épico | Prioridade | Painéis afetados |
| --- | --- | --- | --- |
| E0 | Hierarquia Parceiro e painéis por função | P0 | Todos |
| E1 | Permanência e saída | P0 | Portaria, Console, Turno, Comando, Morador |
| E2 | Console multi-condomínio | P0 | Console, Turno |
| E3 | Operação da portaria (turno, checklist, ronda, achados) | P0 | Portaria, Turno, Comando |
| E4 | Abertura e fechamento | P0 | Portaria, Console, Técnico, Morador |
| E11 | **Log de Segurança e Comando de Segurança** | P0 | Comando, Turno, Condomínio |
| E5 | Lista de bloqueio | P1 | Portaria, Console, Comando, Condomínio |
| E6 | Encomendas avançadas | P1 | Portaria, Morador |
| E7 | Experiência do morador | P0/P1 | Morador |
| E8 | Eventos e convidados | P1 | Portaria, Morador |
| E9 | Emergência e coação | P1 | Morador, Portaria, Console, Comando |
| E10 | Plataforma do parceiro | P1 | Parceiro, Central |

## 7.1. E0 — Hierarquia Parceiro e painéis por função (P0)
- E0.1 Tabelas `platform.partners`, `platform.partner_members` (papéis do parceiro), `platform.partner_tenant_contracts` (vigência, status), `cp.operator_schedules` (escala operador × condomínio × janela).
- E0.2 RLS: acesso da equipe do parceiro ao condomínio por contrato ativo + papel + escala (R3.1–R3.3).
- E0.3 `/entrada` com cartões por contexto e roteamento para o painel (R4.1–R4.5).
- E0.4 Shell de cada painel com menu gerado por capacidades.

## 7.2. E1 — Permanência e saída (P0)
- E1.1 Toda entrada abre uma **permanência** (`cp.stays`) ligada a pessoa, unidade, quem autorizou e origem (manual V014, facial, QR, link).
- E1.2 **Saída prevista**: do convite, da regra do prestador (`visit_rules`) ou do padrão por tipo (prestador, entregador, visitante, obra) configurado pelo condomínio, herdando o padrão do parceiro.
- E1.3 Saída registrada por facial de saída, QR, botão do porteiro/operador ou confirmação do morador.
- E1.4 Alertas em níveis: (1) portaria/console no vencimento; (2) após prazo configurável, morador responsável e supervisor; (3) aberto até saída ou justificativa. Cada nível gera evento no log.
- E1.5 Painel "quem está no condomínio agora" com filtros e destaque das vencidas.
- E1.6 Permanências abertas entram como pendência obrigatória da passagem de turno.
- E1.7 Fechamento do dia: lista de entradas sem saída, como relatório e evidência.
- E1.8 Com facial nas duas direções, a saída é automática; sem ele, manual, com origem visível.

## 7.3. E2 — Console multi-condomínio (P0)
- E2.1 Fila única de eventos dos condomínios escalados (chamada, pedido de abertura, chegada sem convite, permanência, porta aberta, offline), em tempo real (SSE, `realtime`).
- E2.2 Contexto do condomínio ao abrir o evento.
- E2.3 Abertura remota com motivo obrigatório; dupla confirmação em pontos críticos; tudo no log.
- E2.4 Só condomínios da escala (R3.1).
- E2.5 SLA por evento (tempo até atender e até resolver) e `SLA_BREACHED`.
- E2.6 Distribuição de condomínios por operador e turno (Painel do Turno).

## 7.4. E3 — Operação da portaria (P0)
- E3.1 Passagem de turno com relatório (ocorrências, permanências abertas, encomendas pendentes, equipamentos com problema) e **aceite** de quem entra.
- E3.2 Livro digital (ocorrências) com exportação.
- E3.3 Checklist de turno configurável.
- E3.4 Ronda por QR em pontos fixos (sem GPS de funcionário).
- E3.5 Achados e perdidos com foto e retirada identificada.

## 7.5. E4 — Abertura e fechamento (P0)
- E4.1 Comandos remotos pelo gateway, com dupla confirmação configurável.
- E4.2 Eclusa (intertravamento) por par de pontos.
- E4.3 Alertas de porta mantida aberta e abertura forçada, quando o equipamento informar.
- E4.4 Status de equipamentos para parceiro/técnico; status resumido para o morador.
- E4.5 Abertura pelo app do morador, ligável por morador (Facial v1.1).

## 7.6. E11 — Log de Segurança e Comando de Segurança (P0)
- E11.1 Tabela `cp.security_log` (+ `platform.partner_security_log`) com campos, cadeia de hash e grants da seção 5.
- E11.2 Gravação na mesma transação a partir de V014, V033, módulos de acesso (V026–V047) e dos épicos novos.
- E11.3 Âncora diária de hash e verificação de integridade no painel.
- E11.4 Painel Comando de Segurança completo (4.3.4).
- E11.5 Incidentes: abrir, designar, linha do tempo pelo log, encerrar, dossiê com manifesto.
- E11.6 Revisão de aberturas remotas (conferida/irregular).
- E11.7 Busca e exportação do log com manifesto e motivo.
- E11.8 `SENSITIVE_VIEWED` em toda consulta sensível pela equipe.

## 7.7. E5 — Lista de bloqueio (P1)
- E5.1 Restrição com motivo, validade, autor e base legal.
- E5.2 Alerta (`BLOCKLIST_HIT`) quando pessoa restrita é cadastrada, convidada ou reconhecida.
- E5.3 Restrição por unidade (morador) e geral (gestão local + líder, dupla aprovação).

## 7.8. E6 — Encomendas avançadas (P1)
- E6.1 Lembrete de encomenda parada (prazo configurável).
- E6.2 Ações em lote e busca avançada.
- E6.3 Local de guarda/locker como configuração (integração com locker = P2).

## 7.9. E7 — Experiência do morador (P0/P1)
- E7.1 Convidar em 3 toques e compartilhar (P0).
- E7.2 Histórico de quem entrou e saiu, com origem (P0).
- E7.3 Pré-autorização de delivery (P1).
- E7.4 Modo viagem (P1).
- E7.5 Pets e veículos da unidade (P1, sem leitura de placa).
- E7.6 Interfone pelo app (P1, tecnologia a escolher).
- E7.7 Documentos do condomínio (lacuna do PRD v1.0, P1).

## 7.10. E8 — Eventos e convidados (P1)
- E8.1 Aviso à portaria/console na chegada de convidado de evento.
- E8.2 Acesso do convidado só à área e janela da reserva (Facial v1.1 §9.1).

## 7.11. E9 — Emergência e coação (P1)
- E9.1 Alerta de emergência no app e na portaria, para a central e o líder; texto deixa claro que não substitui socorro público.
- E9.2 Senha de coação: abre normalmente e gera alerta silencioso `DURESS_CODE_USED`.

## 7.12. E10 — Plataforma do parceiro (P1)
- E10.1 Cadastro de parceiro e carteira (usa E0).
- E10.2 White-label por parceiro e condomínio, com cores extraídas do logo e prévia (ADR-0010).
- E10.3 Assistente de implantação: torres, unidades por andar, pontos de acesso, equipe.
- E10.4 Condomínio de demonstração isolado.
- E10.5 Cobrança da assinatura do parceiro (Asaas, régua de cobrança).
- E10.6 Pedidos LGPD do titular e exportação dos dados do condomínio.

---

# 8. Fluxos principais

## 8.1. Prestador que não saiu
1. Porteiro registra a entrada do prestador (manual ou facial) → `ENTRY_RECORDED` + `STAY_OPENED` com saída prevista 17h.
2. 17h: `STAY_DUE` → aparece no painel da portaria e na fila do console.
3. 17h + prazo N1: `STAY_OVERDUE_L1` → porteiro liga/verifica; morador responsável recebe push "o prestador ainda não saiu?".
4. Prazo N2: `STAY_OVERDUE_L2` → supervisor e Comando de Segurança veem em vermelho.
5. Saída registrada (`EXIT_RECORDED` + `STAY_CLOSED`) **ou** justificativa com motivo (`STAY_JUSTIFIED`).
6. Na troca de turno, se ainda aberta, entra como pendência obrigatória.

## 8.2. Abertura remota
1. Visitante aperta o interfone → evento na fila do console do operador escalado.
2. Operador vê contexto, liga para a unidade, morador autoriza.
3. Operador abre com motivo ("autorizado por unidade 52 por ligação") → `REMOTE_OPEN` (+ `DOUBLE_CONFIRM_GIVEN` se ponto crítico).
4. Se houver eclusa, segundo portão só abre após o primeiro fechar (`INTERLOCK_BLOCKED` se tentar antes).
5. No dia seguinte, o líder revisa aberturas remotas por amostragem/filtro → `REMOTE_OPEN_REVIEWED`.

## 8.3. Coação
1. Porteiro digita a senha de coação no painel (ou morador no app) → a porta abre normalmente.
2. `DURESS_CODE_USED` crítico vai **só** ao Comando de Segurança e ao supervisor, sem sinal visível no local.
3. Líder abre incidente, segue o protocolo do parceiro (fora do sistema) e registra o desfecho.

## 8.4. Passagem de turno
1. Porteiro que sai gera o relatório (ocorrências, permanências abertas, encomendas, equipamentos) → `SHIFT_HANDOVER_SUBMITTED`.
2. Porteiro que entra confere e aceita → `SHIFT_HANDOVER_ACCEPTED`. Sem aceite em X minutos, alerta ao supervisor.

---

# 9. Dados novos (propostos)

| Tabela | Finalidade | Observações |
| --- | --- | --- |
| `platform.partners` | Parceiros | status, white-label |
| `platform.partner_members` | Equipe e papel no parceiro | papéis da seção 4.1 |
| `platform.partner_tenant_contracts` | Vínculo parceiro × condomínio | um ativo por condomínio (R3.4) |
| `cp.operator_schedules` | Escala operador × condomínio | janela de validade |
| `cp.stays` | Permanências | saída prevista, nível de alerta, origem |
| `cp.stay_rules` | Tempos padrão por tipo | herança parceiro → condomínio |
| `cp.ops_queue_events` | Fila da central | SLA, atribuição |
| `cp.shift_handovers` | Passagem de turno | relatório + aceite |
| `cp.checklists`, `cp.checklist_runs` | Checklist de turno | — |
| `cp.patrol_points`, `cp.patrol_reads` | Ronda por QR | sem GPS |
| `cp.lost_found_items` | Achados e perdidos | foto em `files` privados |
| `cp.blocklist_entries` | Lista de bloqueio | validade, base legal, aprovações |
| `cp.incidents` | Incidentes | ligados ao log por `correlation_id` |
| `cp.security_log` / `platform.partner_security_log` | Log de Segurança | seção 5 |
| `cp.unit_pets`, `cp.unit_vehicles` | Pets e veículos | — |
| `cp.absences` | Modo viagem | — |
| `cp.documents`, `cp.document_versions` | Documentos do condomínio | arquivos privados |

Todas com `tenant_id` (exceto `platform.*`), RLS forçado, grants por coluna e migrations Flyway a partir de **V048**.

---

# 10. Integrações

| Integração | O que troca | Direção | Prioridade |
| --- | --- | --- | --- |
| App Advanced | Unidades e moradores, avisos e assembleias publicados, pedidos/ocorrências → chamado | Dois sentidos | P1 (contrato de API desde já) |
| Facial: Control iD, Hikvision, Dahua, Intelbras | Credenciais, eventos de entrada/saída, saúde, comandos | Gateway local e nuvem | P0 (homologar o primeiro) |
| Interfone/telefonia (SIP ou voz em nuvem) | Chamadas portaria ↔ unidade ↔ central | Dois sentidos | P1 |
| Central de alarme do parceiro | Coação, emergência, arrombamento | CondPulse → parceiro | P1 (protocolo por parceiro) |
| Câmeras (CFTV/VMS) | Imagem do ponto no evento | Leitura | P2 |
| Locker | Depósito/retirada | Dois sentidos | P2 |
| Apple/Google Wallet | Convite recorrente | CondPulse → celular | P2 |
| Asaas | Assinatura do parceiro | CondPulse → Asaas | P1 |
| Push e WhatsApp | Avisos (WhatsApp nunca para login) | CondPulse → morador | P0 (push depende do Expo) |

---

# 11. Fora do escopo

| Item | Onde fica |
| --- | --- |
| Financeiro condominial (V036) | Módulo Administradora (congelado) |
| Assembleias, atas, votações formais, procurações | Advanced (CondPulse só mostra aviso) |
| NPS/pesquisas de síndico | Advanced (CondPulse pode disparar ao morador a pedido do Advanced) |
| Contratos, cotações, fornecedores, compras | Advanced |
| Infrações e multas | Advanced (portaria registra ocorrência e encaminha) |
| Cockpit de síndico, prepostos | Advanced |
| GPS/cerca virtual de funcionários | Advanced |
| IA que decide acesso | Proibido |
| Feed social, stories, gamificação, classificados | Fora do lançamento |

---

# 12. Roadmap por ondas

| Onda | Conteúdo | Portão para a próxima |
| --- | --- | --- |
| 0 — Base pronta | Aceitar tickets do v2.0, login real com MFA, facial homologado com 1 marca, app Expo no aparelho | Piloto liberado |
| 1 — Núcleo de segurança | E0, E1, E2, E3, E4, E11 | Piloto com 1 parceiro e 1 condomínio |
| 2 — Morador e encomendas | E6, E7, E8 | Vários condomínios do mesmo parceiro |
| 3 — Parceiro e escala | E5, E9, E10, integração Advanced | Segundo parceiro |

Datas saem do fechamento da Onda 0.

---

# 13. Backlog de tickets (CPX)

Critério comum de aceite (vale para todos): migrations com RLS e teste de acesso cruzado; evento no Log de Segurança quando a ação for de segurança; testes de backend e web verdes; tela conferida em celular e desktop; documentação em `docs/`.

| Ticket | Épico | Entrega | Aceite específico |
| --- | --- | --- | --- |
| CPX-001 | E0 | Tabelas de parceiro, membros, contratos | Equipe de parceiro sem contrato ativo não lê nenhuma linha do condomínio |
| CPX-002 | E0 | Escala de operadores | Operador fora da escala recebe 403 e não vê o condomínio na fila |
| CPX-003 | E0 | Capacidades novas (4.4) | Matriz papel × capacidade testada por papel |
| CPX-004 | E0 | `/entrada` por contexto e roteamento por papel | Cada papel cai no seu painel; multi-papel vê cartões |
| CPX-005 | E0 | Shell dos painéis com menu por capacidade | Item sem capacidade não aparece e a API recusa |
| CPX-006 | E11 | `cp.security_log` + cadeia de hash + grants | Runtime sem UPDATE/DELETE; `DatabaseRoleGuard` recusa grants indevidos |
| CPX-007 | E11 | Gravação na mesma transação (V014, V033, acesso) | Falha no log desfaz a operação |
| CPX-008 | E11 | Âncora diária e verificação de integridade | Alteração manual em ambiente de teste é detectada |
| CPX-009 | E11 | Busca e filtro do log | Filtros por condomínio, tipo, severidade, autor, período |
| CPX-010 | E11 | Exportação com manifesto | Exporta só com motivo; gera `LOG_EXPORTED` |
| CPX-011 | E11 | `SENSITIVE_VIEWED` | Toda consulta sensível pela equipe gera evento |
| CPX-012 | E11 | Comando de Segurança – visão da carteira | Semáforo por condomínio com dados reais |
| CPX-013 | E11 | Incidentes | Linha do tempo a partir do log; dossiê exportado |
| CPX-014 | E11 | Revisão de aberturas remotas | Marcação gera `REMOTE_OPEN_REVIEWED` |
| CPX-015 | E1 | `cp.stays` + regras de tempo | Toda entrada abre permanência; saída fecha |
| CPX-016 | E1 | Worker de alertas N1/N2 | Alertas no horário com tolerância definida; idempotentes |
| CPX-017 | E1 | Painel "quem está agora" | Vencidas destacadas; origem visível |
| CPX-018 | E1 | Confirmação de saída pelo morador | Só morador da unidade; gera evento |
| CPX-019 | E1 | Relatório de fechamento do dia | Lista entradas sem saída |
| CPX-020 | E2 | Fila única em tempo real | Evento novo aparece sem recarregar |
| CPX-021 | E2 | Abertura remota com motivo e dupla confirmação | Sem motivo, recusa; ponto crítico exige 2ª confirmação |
| CPX-022 | E2 | SLA e Painel do Turno | `SLA_BREACHED` gerado; redistribuição registrada |
| CPX-023 | E3 | Passagem de turno com aceite | Sem aceite em X min alerta supervisor |
| CPX-024 | E3 | Checklist de turno | Configurável por condomínio |
| CPX-025 | E3 | Ronda por QR | Ponto perdido gera `PATROL_MISSED` |
| CPX-026 | E3 | Achados e perdidos | Retirada identificada |
| CPX-027 | E4 | Comandos remotos pelo gateway | Funciona com o adaptador homologado |
| CPX-028 | E4 | Eclusa | Segundo ponto não abre com o primeiro aberto |
| CPX-029 | E4 | Porta aberta / forçada | Alerta quando o equipamento informa |
| CPX-030 | E4 | Abertura pelo app (ligável por morador) | Desligado, botão some e API recusa |
| CPX-031 | E5 | Lista de bloqueio com dupla aprovação | Geral só ativa com 2 aprovações |
| CPX-032 | E5 | `BLOCKLIST_HIT` | Alerta ao console e ao líder |
| CPX-033 | E6 | Lembrete de encomenda parada | Configurável; sem duplicar aviso |
| CPX-034 | E6 | Lote e busca avançada | — |
| CPX-035 | E7 | Convite em 3 toques + compartilhar | Testado em iOS e Android |
| CPX-036 | E7 | Delivery pré-autorizado e modo viagem | Janela expira sozinha |
| CPX-037 | E7 | Pets e veículos | Visível à portaria |
| CPX-038 | E7 | Documentos do condomínio | Arquivos privados com versão |
| CPX-039 | E7 | Interfone pelo app | Após escolha da tecnologia |
| CPX-040 | E8 | Aviso de convidado de evento | Aparece na portaria e no console |
| CPX-041 | E9 | Emergência e coação | Coação sem sinal no local; chega ao líder |
| CPX-042 | E10 | Assistente de implantação | Gera torres/unidades por andar |
| CPX-043 | E10 | White-label com extração de cores | Contraste mínimo validado |
| CPX-044 | E10 | Cobrança da assinatura (Asaas) | Régua de cobrança; suspensão automática configurável |
| CPX-045 | E10 | LGPD do titular e exportação | Pedido registrado e respondido no prazo |
| CPX-046 | E10 | Condomínio demo | Isolado, dados sintéticos |

---

# 14. Métricas de sucesso

| Métrica | Como mede | Meta |
| --- | --- | --- |
| Permanências sem saída no fim do dia | Abertas ÷ entradas do dia | Definir no piloto |
| Tempo até tratar alerta de permanência | Alerta → saída ou justificativa | Definir no piloto |
| Tempo de atendimento da central | Evento → operador atende | SLA por parceiro |
| Passagens de turno com aceite | Aceitas ÷ turnos | Definir no piloto |
| Aberturas remotas com motivo | Com motivo ÷ total | 100% (regra do sistema) |
| Integridade do log | Dias com cadeia íntegra ÷ dias | 100% |
| Ativação de moradores | Unidades com morador ativo ÷ unidades | 70% (PRD v1.0) |

---

# 15. Riscos e decisões pendentes

Decisões do responsável:
- [ ] Confirmar o congelamento do financeiro condominial (V036).
- [ ] Confirmar a hierarquia Master → Parceiro → Condomínio e quem paga (parceiro, condomínio ou ambos).
- [ ] Escolher o parceiro piloto e o equipamento facial do piloto.
- [ ] Escolher a tecnologia do interfone.
- [ ] Definir quem pode criar restrição geral e a base legal.
- [ ] Definir prazos de retenção do Log de Segurança com o responsável por privacidade.

Riscos:
- Facial só simulado: E1.8, E4 e parte do E11 dependem de equipamento real.
- Push depende do app Expo sem build.
- Biometria, lista de bloqueio e log exigem cuidado com LGPD.
- `docs/STATUS.md` desatualizado (V044–V047): conferir o estado real antes de quebrar em tickets.
- Landing atual do Lovable fala com síndico; a nova home precisa falar com empresa de segurança e morador.

---

# 16. Instrução para os agentes

1. Não começar este PRD antes do portão da Onda 0.
2. Começar por **CPX-001 a CPX-011** (hierarquia + log): todo o resto grava no log.
3. Cada ticket: branch própria, migrations a partir de V048, testes verdes antes de avançar, PR com evidências em `docs/evidence/`.
4. Nunca dar `UPDATE`/`DELETE` no log; correção é novo evento.
5. Nunca criar função de síndico ou de administradora; se aparecer necessidade, registrar como ponto de integração com o Advanced.
