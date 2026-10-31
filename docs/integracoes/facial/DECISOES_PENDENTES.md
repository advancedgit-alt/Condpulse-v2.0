# Acesso facial — decisões do responsável e parâmetros

Registro das decisões que orientam a trilha (a) — domínio e backend (V037 em diante). Cada item diz se é **decisão do
responsável** (com data) ou **provisória** (vale até o responsável responder) e onde está o parâmetro. Nada aqui foi
aplicado em banco, Fly ou Vercel; a aplicação fica com o executor protegido.

## Decisões do responsável (31/10/2026)

| # | Decisão | Como fica no código | Situação |
|---|---|---|---|
| D1 | Piloto no **Condomínio Teste 1** (homologação); liberação para os outros condomínios depois do piloto. | Tudo parametrizado por condomínio; flag global `CP_FACIAL_ACCESS_ENABLED=false`. | Vigente |
| D2 | Quatro marcas (Control iD, Hikvision, Dahua, Intelbras) completas e parametrizadas por condomínio. | Catálogo e homologações em V040 (próximas fatias). | Vigente |
| D3 | **MASTER de tudo**: a conta indicada pelo responsável (já `PLATFORM_ADMIN`) é MASTER da plataforma e MASTER de implantação, inclusive no Condomínio Teste 1. Concessão do MASTER de implantação a essa conta no Condomínio Teste 1 **autorizada explicitamente**. | Roteiro protegido [`ops/sql/technical-grant-master-implantacao.sql`](../../../ops/sql/technical-grant-master-implantacao.sql) (padrão de `ops/sql/platform-admin-grant.sql`), executado depois de aplicar a V037. O e-mail não fica no repositório: o executor informa o UUID da conta e do condomínio. | **Pendente de execução** pelo executor protegido |
| D4 | **Abertura remota com dois modos, ambos prontos**: portaria local; portaria remota em que o morador libera a entrada pelo app. A opção do morador liga/desliga **por morador e por condomínio** e controla se aparece no app. Ponto de extensão documentado para liberação por IA (voz), **sem implementar agora**. | Fatia V048 (recibos de abertura) e parâmetros por condomínio/morador. | A implementar (V048) |
| D5 | **Gateway local e nuvem**, ambos prontos, como parâmetro **por condomínio e por equipamento**. A identidade do gateway e os endpoints edge servem aos dois: gateway local e serviço de nuvem que recebe os aparelhos conectados de fora. | Fatia V047 (identidade do gateway) e `/v1/edge/*`. | A implementar (V047) |

### Consequência do D3 sobre a regra "ninguém concede papel a si próprio"

- Pelo painel (`POST /v1/platform/tenants/{t}/technical-grants`) a regra continua: a conta autorizadora nunca é a
  beneficiada (CHECK no banco, política RLS e serviço). Como a mesma conta é MASTER da plataforma e de implantação, a
  concessão para ela **não sai pelo painel**.
- Ela sai pelo caminho operacional (`granted_via=OPERATIONS`), executado fora da role runtime, com o motivo e a
  referência da aprovação do responsável gravados em `reason` e a identidade do banco em `granted_by_db_user`.

## Decisões provisórias (até o responsável responder)

| # | Tema | Valor provisório | Parâmetro |
|---|---|---|---|
| P1 | Validade máxima da concessão técnica pelo painel | 30 dias (teto rígido do banco: 90) | `CP_TECHNICAL_GRANT_MAX_DAYS` |
| P2 | Escopos padrão do MASTER de implantação | instalação (ler, cadastrar, vincular canal, comissionar), rotação de segredo, gateway e pedido de homologação; **sem** `person.review`, `access.request_open` e `audit.export` (só por pedido específico) | `TechnicalScope.implantationDefaults()` e o roteiro operacional |
| P3 | Prova de idade | data de nascimento cifrada no serviço (AES-256-GCM, chave `CP_AGE_EVIDENCE_KEY` fora do banco, ligada a condomínio/sujeito/evidência) **ou** atestação verificada; a gestão só vê "atende / não atende / desconhecido" para N anos. Sem a chave, só a atestação funciona (data protegida responde 503). Quem verifica: gestão (`memberships.manage`) ou `person.review` técnico delegado | V039 (`cp.age_evidence`) |
| P4 | Nascidos em 29/02 | completam anos em 01/03 em ano não bissexto (`MARCH_1`); alternativa `FEBRUARY_28`; **parâmetro do condomínio** | `cp.facial_access_settings.leap_day_rule` (V039) |
| P5 | Cadastro facial de menores | desligado por padrão, parâmetro do condomínio (gravado na V039; aplicado no cadastro facial da V049) | `cp.facial_access_settings.minor_face_enrollment_enabled` |
| P6 | Cartão, QR e PIN | mesmas regras de área que a face | Fatias V043/V044 |
| P8 | Proprietário (`OWNER`) como contexto de acesso | `RESIDENT` quando a unidade não está alugada; `OWNER_NONRESIDENT` com locação em vigor (V034). A troca de situação invalida o contexto materializado até a próxima materialização | `cp.membership_context_kind` (V038) |
| P9 | Quem materializa sujeitos | Gestão para contas com vínculo físico (morador, responsável, dependente, proprietário, portaria); portaria para visitantes de convites vigentes (a gestão não lê convites). `MANAGER` e `SUPPORT` não geram contexto físico | `POST .../management/access-subjects/materialize` e `.../concierge/access-subjects/materialize` |
| P10 | Revogação de contexto | Vale até reautorização explícita da gestão (`/subject-contexts/{id}/reauthorize`); a materialização nunca a desfaz. Contexto de convite revogado não volta: novo convite é nova origem | V038 (gatilho e `reauthorizes_context_id`) |
| P11 | Validade da evidência de idade | 365 dias (1 a 3660), parâmetro do condomínio | `cp.facial_access_settings.age_evidence_validity_days` |
| P12 | Aprovador da homologação | Não se exige aprovador distinto de quem criou o dossiê (há um único MASTER, D3); a trilha guarda quem pediu e quem decidiu. Catálogo semeado com os 27 modelos do pacote como candidatos documentados (SKU, revisão e região UNKNOWN até o G0) | V040 |
| P13 | Área × ponto | Gestão cria e edita área lógica e interdita; rota essencial, separação física e todos os pontos (origem/destino, mecanismo, sentido, saída) são do MASTER de implantação; ponto só fica COMMISSIONED pelo aceite técnico (V042) e mudança física de ponto comissionado o devolve a MAPPED | V041 |
| P14 | Aceite de instalação | Assinatura das regras pela gestão com `area.manage` e o próprio vínculo MANAGER (até existir `policy.publish` na V043); aprovação técnica só com `installation.commission`, sem exigir pessoa distinta (D3). Combinação APROVADO_PILOTO basta no piloto; `review_due_at` vencido bloqueia. Mudança física de ponto comissionado suspende o vínculo ativo; equipamento trocado é nova instância | V042 |
| P15 | Política de ingresso | Aprovação da regra por `policy.publish` sem exigir pessoa distinta do autor (D3); validação vale 24 h e cai com nova revisão de instalação aprovada; antirretorno, lotação, acompanhamento físico e calendários de feriado bloqueiam a validação até existirem; simulação só sintética até os direitos (V044), sem gravar decisão (decisões entram com os eventos, V046) | V043 |
| P16 | Direitos derivados (AC-009) | Vigência máxima de 30 dias por derivação (renovada por nova derivação), nunca além do contexto, convite, reserva com tolerâncias da regra, evidência de idade e revisão; derivação pela gestão com `memberships.manage` + `policy.publish`, a partir de revisão APPROVED (a publicação por destino é a V045); reserva: só anfitrião morador/dependente da unidade e convidado listado (regra HOST_OR_LISTED_GUEST) | `cp.access_grant_horizon()` e `GrantPlanner.HORIZON` (V044) |
| P17 | Revogação (AC-015, parte) | Bloqueio de sujeito e contexto revogado revogam também cartões/QR do vínculo (CP-046) na mesma transação; contexto substituído/expirado revoga só os direitos. Cancelamento de convite/reserva e mudança de vínculo derrubam o direito na hora (`cp.access_grant_current`) e recebem a marca na varredura seguinte (derivação). Derivação revogada não é refeita com os mesmos fatos | V044 (`cp.revocation_markers`) |
| P18 | Publicação por destino (AC-012/AC-013) | Um trabalho por sujeito × equipamento com o documento compilado pelo banco (direitos vigentes nas passagens comissionadas com vínculo ativo; sentido UNKNOWN não publica); versão desejada monotônica; o trabalho novo supera o anterior em aberto. Trabalho não é sucesso: só o executor relata ACK/APPLIED_UNVERIFIED/CONFIRMED (leitura de volta com o mesmo hash)/PARTIAL/FAILED/UNKNOWN. Cada marca da V044 publica a retirada na mesma transação (REVOKE_ALL para sujeito restrito) com prioridade 0. Até 512 entradas por documento e 2000 sujeitos por publicação total (acima disso, por lista). Leitores pelo `direction_map` comissionado. Só regra de tempo integral é publicável: janela semanal ainda não é representada no documento e fica fora do documento (relatada por destino como `SCHEDULE_NOT_REPRESENTABLE`) e o compilado retira o que estava publicado, nunca acesso contínuo. Derivação e publicação registram recusa do banco por item (`DB_REFUSED`) sem desfazer o lote | V045 (`cp.rights_publications`) |
| P7 | Gestor (MANAGER) | sem herança de escopo técnico; `area.manage` entrou na V041 (área lógica, nome, hierarquia, interdição); `policy.edit`/`policy.publish` entraram na V043 | `StaffCapability` |

## Perguntas ainda abertas

0. Chave `CP_AGE_EVIDENCE_KEY` (32 bytes, base64) para a data protegida: precisa ser gerada e cadastrada pelo executor protegido (nenhuma foi criada).

1. Cofre de segredos das credenciais de fabricante (serviço e custo) — sem isso, `connector.rotate_secret` só registra referência.
2. Acesso de parceiro à documentação licenciada (Hikvision TPP, SDK Dahua).
3. Processo do responsável legal para biometria de menores, quando o condomínio quiser ligar (P5).
