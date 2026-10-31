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
| P3 | Prova de idade | data de nascimento cifrada/protegida; projeção só de "maior de N" para o gestor | Fatia V039 |
| P4 | Nascidos em 29/02 | completam anos em 01/03 em ano não bissexto; critério é **parâmetro do condomínio** | Fatia V039 |
| P5 | Cadastro facial de menores | desligado por padrão, parâmetro do condomínio | Fatia V049 |
| P6 | Cartão, QR e PIN | mesmas regras de área que a face | Fatias V043/V044 |
| P7 | Gestor (MANAGER) | sem herança de escopo técnico; capacidades de regra (`area.manage`, `policy.edit`, `policy.publish`, `person.review`) entram nas fatias que as usam (V041/V043) | `StaffCapability` |

## Perguntas ainda abertas

1. Cofre de segredos das credenciais de fabricante (serviço e custo) — sem isso, `connector.rotate_secret` só registra referência.
2. Acesso de parceiro à documentação licenciada (Hikvision TPP, SDK Dahua).
3. Processo do responsável legal para biometria de menores, quando o condomínio quiser ligar (P5).
