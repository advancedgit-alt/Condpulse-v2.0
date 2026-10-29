# Entrega do primeiro MVP CondPulse

Estado: direção de entrega autorizada pelo responsável; detalhamento proposto
para revisão, organizado em seis fases de trabalho remanescentes. Referência:
PRD CondPulse v1.0, [plano de implantação](PLANO_IMPLANTACAO.md)
e [ADR-0007](adr/0007-tenancy-e-promocao-de-releases.md).

As fases organizam a entrega solicitada; não substituem os 68 tickets ou os 60
casos de aceite do PRD. Implementação, validação no CI e aceite com os serviços
reais permanecem estados distintos. Não há data de entrega prometida por esta
contagem.

## Marcos e situação de partida

- Depois das fases 1 a 3: primeiro teste restrito do condomínio, com login real,
  seleção de condomínio/unidade, painel do morador, gestão local mínima de
  unidades/moradores/áreas/regras e reservas no recorte aprovado.
- Depois das fases 1 a 6: MVP ampliado para teste, com master, financeiro SaaS
  básico e processo demonstrado de atualização central. Ainda não é aceite do
  produto completo, integração física ou lançamento comercial.

Atualização de 29/10/2026: a base contém migrations V001-V017 e os módulos das
fases 2 a 5 e da F2 (gestão local, convites e primeiro acesso, recuperação, MFA,
painel master, financeiro, encomendas, visitas, acessos, caixa interna, avisos e
pedidos). A homologação (`rpnrqfrfwywglvyykgrg`) recebeu V006-V017 pelo executor
protegido e a Vercel Production ligou BFF, reservas e as flags desses módulos, com
smoke público verde; recuperação por e-mail (sem SMTP) e worker seguem desligados.
O onboarding de homologação foi aplicado em 29/10/2026 com aprovação do
responsável (um condomínio, unidade 101, uma conta com vínculos de gestão e
morador até 28/11/2026, área "Salão de festas"; inspect 40917252737, apply
40917560570). Nenhum papel de plataforma foi concedido e o ensaio formal de login
real com MFA da gestão ainda não foi registrado, então não há aceite das jornadas;
consultar [STATUS.md](STATUS.md) e as evidências de cada source.

O rótulo Vercel `Production` do deployment atual identifica o escopo de hospedagem;
não comprova aceite de produção operacional. Nenhum recurso novo faturável foi
criado por este lote de planejamento.

Painel master e financeiro básico existem no código e estão ligados na homologação,
sem uso real ainda; flags por tenant e promoção de release demonstrada continuam
inexistentes. O segundo projeto Supabase foi criado após
autorização de custo, conforme [AMBIENTES.md](AMBIENTES.md); a integração e a
separação completa dos ambientes continuam pendentes.

## Seis fases e seus critérios de conclusão

| Fase | Entrega | Gate para concluir | Estado de partida |
| --- | --- | --- | --- |
| 1. Ambiente de teste e atualizações | Inventário homologação/produção, tenant de teste e processo central de versões | Separação efetiva de dados/Auth/Storage/segredos/origens, ingresso restrito, responsável e escopo/regras aprovados; custos novos aceitos antes de provisionar | Parcial: dois projetos Supabase próprios; homologação na V017 com flags ligadas (29/10/2026); produção (`tkxcujckpsuawnzkvpjg`) só com o schema V001-V017 (migrate 40884195154), sem runtime, Auth, Storage nem aplicação ligada; tenant de teste cadastrado na homologação (apply 40917560570) |
| 2. Acesso ao sistema | Cadastro operacional, primeiro acesso, recuperação de acesso e MFA/AAL2 administrativo | Principal mínimo conferido; `inspect`/revisão/ACK/`apply` e recibo; Auth real, cookies/CSRF, refresh/logout, recuperação e negações validados; MFA/AAL2 administrativo exigido no backend | Onboarding gerenciado executado na homologação (inspect 40917252737, apply 40917560570); primeiro acesso por convite, recuperação e MFA/AAL2 administrativo implementados; BFF ligado na homologação, recuperação desligada sem SMTP; login real pendente |
| 3. Painel do condomínio | Painel residencial e gestão local mínima de unidades, moradores/vínculos, áreas/regras e reservas | Contratos e telas administrativas implementados com autorização/MFA/auditoria; percurso real no navegador/API/banco, conflito/replay/revogação e isolamento entre contextos; aceite do teste restrito registrado | Interface residencial, reservas e API/telas de gestão local implementadas e ligadas na homologação; condomínio de teste cadastrado; percurso real com Auth/MFA e aceite pendentes |
| 4. Painel master | Operação global dos condomínios e escopo administrativo de plataforma | Autorização de plataforma, MFA/AAL2 e auditoria; listagem/cadastro/estado de condomínio aprovados; testes negando acesso cruzado e poder master a gestor local | Implementado e ligado na homologação (V010 aplicada em 29/10/2026); concessão do primeiro papel, Auth real/AAL2 e aceite pendentes |
| 5. Financeiro da plataforma | Contratos/planos aprovados e situação de cobrança da assinatura | Valores/vigências explícitos, registros rastreáveis e permissões testadas; estados financeiros consistentes e sem falso pagamento confirmado | Implementado e ligado na homologação (V011 aplicada em 29/10/2026), só com registro manual de pagamento; valores reais, Auth real/AAL2 e aceite pendentes; provedor de pagamento não contratado/integrado por este plano |
| 6. Homologação e liberação | Validar versão no tenant de teste e promovê-la à produção central | Artefato aprovado identificado, configuração/migrations compatíveis, promoção e retorno da aplicação demonstrados; smoke/observação, capacidade e recuperação compatíveis com o escopo do piloto | Git/CI/publicação e smoke existem; promoção protegida, restore e ensaio de expansão pendentes |

As fases 4 e 5 podem ser preparadas enquanto os dados de piloto são reunidos,
preservando revisão e contratos. Os gates de ambiente, autenticação e autorização
continuam obrigatórios antes de qualquer acesso real. Controles de release da
fase 6 começam na fase 1: não esperar a expansão para isolar testes e produção.

Rastreabilidade principal: CP-004 para ambientes; CP-011/CP-013/CP-020 para acesso;
CP-014/CP-021/CP-023 para gestão local; OPS-01 para operação SaaS; CP-051 a
CP-056 e CP-066/CP-067 para verificação e liberação. O financeiro básico é o
acréscimo solicitado pelo responsável, com contratos próprios a revisar. Essa
associação não altera o estado de aceite dos tickets.

## Gate do primeiro acesso de teste: fases 1 a 3

O primeiro acesso será liberado somente ao grupo nomeado de teste no ambiente
restrito. O aceite deve conferir a aplicação no navegador, as respostas da API e
os efeitos persistidos, com contas e regras aprovadas. O percurso mínimo é:

1. Entrar com Auth real; verificar sessão opaca, CSRF/Origin, renovação e saída.
2. Selecionar apenas vínculos vigentes, carregar a unidade correta e limpar dados
   anteriores na troca/revogação de contexto.
3. Abrir o painel residencial e consultar uma área real aprovada, com política e
   fuso explícitos. A consulta de ocupação não identifica outras unidades.
4. Criar uma reserva, conferir resposta e persistência, repetir a mesma intenção
   idempotente sem duplicar e cancelar conforme autoria/prazo/versão.
5. Negar conflito de horário, outro tenant/unidade, vínculo revogado, conta
   suspensa e comandos sem autorização. Rede instável não pode virar sucesso
   otimista ou reenvio cego.
6. Exercitar como gestor local apenas as operações aprovadas de unidades,
   moradores/vínculos, áreas/regras e reservas, com MFA/AAL2 validado no backend,
   auditoria e negações para morador e gestor de outro condomínio. APIs/telas
   dessa gestão precisam ser implementadas; não se trata apenas de ligar flags.

O subset inicial não atende áreas com aprovação obrigatória, fila, recorrência,
convidados ou bloqueios administrativos. Cadastro de um `MANAGER` pelo onboarding
não entrega tela de gestão nem MFA. Login de morador não libera ações de gestor ou
master. As transições administrativas de reserva que entrarem no recorte da fase 3
exigem contrato e implementação próprios, sem serem declaradas atendidas pelo
cancelamento residencial existente. Recuperação de acesso e MFA administrativos
continuam gates próprios;
qualquer fluxo ainda ausente deve ter limitação e procedimento aprovado antes de
usuários dependerem dele.

## Dados e dependências que faltam para o piloto

O responsável precisa aprovar no canal privado o condomínio/fuso, unidades,
usuários e seus papéis/vigências, além de uma área com todas as regras de reserva.
Os UUIDs Auth precisam corresponder a contas reais confirmadas; não serão
inventados ou obtidos a partir de `user_metadata` como autorização.

O manifesto exige ao menos um gestor sem unidade e um vínculo residencial com
unidade. A mesma conta pode acumular os vínculos somente mediante aprovação
explícita. Não conceder o papel de gestor por conveniência técnica. Regras de
área incluem capacidade, taxa informativa, limpeza, versão, dias/horários,
antecedência, duração, cota e cancelamento; não há padrão permissivo silencioso.

Também faltam o principal restrito `cp_onboarding_*`, credencial Auth Admin apenas
no cofre/job isolado e novos Secrets operacionais. O wrapper protegido foi preparado,
mas não houve execução gerenciada. Ver [EXECUTOR_ONBOARDING.md](EXECUTOR_ONBOARDING.md).
A chave publicável BFF já
preparada não substitui Auth Admin. O fluxo operacional não cria ou altera
usuários Auth e não recebe credenciais na API/web/worker. Ver
[OPERACAO_ONBOARDING.md](OPERACAO_ONBOARDING.md).

O cadastro pelo CLI/wrapper é uma operação administrativa isolada. Não implementa
convite/primeiro acesso orientado, recuperação de acesso do usuário ou MFA/AAL2
administrativo. Esses fluxos da fase 2 exigem telas/contratos, validação no backend
e ensaio real próprios. Preparar o wrapper não conclui a fase 2.

Outro ambiente exige revisão dos executores que hoje fixam o projeto remoto
admitido, suas CA/credenciais e guardas. Não reaplicar baseline/migrations já
concluídas no projeto existente nem repetir o apply de chaves BFF sem nova causa.
Um ambiente novo segue seu próprio processo Flyway revisado.

## Master, tenants e financeiro no MVP ampliado

O master administra a plataforma: cadastro/estado dos condomínios, acesso às
operações autorizadas, contrato/plano, situação financeira e visão operacional
mínima. O perfil Administrador SaaS e o fluxo OPS-01 do PRD tratam contrato, capacidade,
configuração e suporte temporário justificado; não dão acesso ao conteúdo dos
moradores por padrão nem autorizam personificação silenciosa. A gestão local
administra somente seu condomínio e suas regras, dentro das capacidades
implementadas. APIs e telas administrativas precisam de contrato,
revisão e teste; não existem por a tabela `platform.tenants` já existir.
O identificador técnico do perfil de plataforma e suas capacidades serão definidos
no contrato de implementação; não se presume um enum de papel já existente.

O financeiro desta entrega é a relação comercial da assinatura CondPulse, como
expansão solicitada e autorizada pelo responsável ao recorte original.
Contabilidade condominial, folha, rateio e emissão de boleto ficam fora do recorte.
Cobrança automática/gateway/emissão fiscal não podem ser anunciados como ativos
sem integração e aceite específicos. Pagamento registrado manualmente precisa de
origem, responsável e auditoria. Atraso da assinatura não bloqueia automaticamente
a passagem física de moradores.

O detalhamento de campos e ações da gestão local pertence aos contratos da fase 3;
operações globais, dados agregados e transições financeiras pertencem às fases 4/5.
Esses contratos serão revisados sem presumir preços, benefícios, cancelamentos ou
acesso global irrestrito.

## Como as atualizações chegarão aos condomínios

Uma única base de código gera a release central. PR/CI e homologação do tenant de
teste precedem o aceite e a promoção dos artefatos aprovados. Produção tem seus
próprios dados/segredos; migrations são executadas pelo Flyway separado. A versão
promovida alcança os condomínios da aplicação compartilhada.

O capítulo 28 do PRD prevê funcionalidades controladas por tenant, no servidor e
com auditoria. Esse requisito ainda não está implementado. Flags por tenant, caso
necessárias para liberar uma funcionalidade primeiro ao piloto, exigem
implementação e testes específicos. Hoje só há flags por ambiente.
Tenant de teste em produção, deploy Ready e readiness200 não substituem a
homologação isolada. A integração Git atual precisa ser ajustada/verificada para
que merge de desenvolvimento não publique diretamente aos futuros clientes.

Antes da expansão, registrar compatibilidade de migrations, retorno da aplicação,
comportamento de sessões, limite agregado de conexões, cold start/falhas, carga
representativa e recuperação do banco/objetos. Não declarar metas de capacidade,
RPO/RTO ou disponibilidade com base em um GET de saúde ou backup listado.

## Evidência e limite de entrega

Cada fase registra PR/head e revisão, ambiente/alvo, comandos e jornadas, resultado
e limitações. Dados pessoais/manifestos/credenciais permanecem fora do Git e dos
logs públicos. Um resultado `UNKNOWN` de escrita exige reconciliação antes de
seguir; não repetir apply automaticamente.

O plano não marca tickets como aceitos. Visitantes, portaria, encomendas,
avisos/documentos/pedidos, importação, aplicativo Expo, notificações e integração
física continuam no backlog, com seus próprios gates. Nenhuma dessas jornadas é
declarada pronta pela conclusão do primeiro teste restrito ou deste MVP ampliado.

O capítulo 30 do PRD exige 100% dos P0 para o piloto operacional. Os marcos das
fases 3/6 autorizam somente testes técnicos controlados do recorte descrito;
não substituem aquele gate, o processo oficial do condomínio ou o aceite de
lançamento comercial. O escopo restante do PRD continua obrigatório para esses
marcos operacionais mais amplos.
