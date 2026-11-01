# Roteiro de testes na homologação com o condomínio demo

Para o responsável testar, com login real e segundo fator, cada jornada que existe hoje no CondPulse, usando o
condomínio demo (CPX-046, V055, [ADR-0012](adr/0012-condominio-demo.md)). Tudo aqui é homologação
(`https://project-rhvdq.vercel.app`); nada deste roteiro vale para o segundo projeto Supabase (`tkxcujckpsuawnzkvpjg`),
que não recebe a V055 sem OK explícito.

Este roteiro descreve só telas que existem no código desta base. Onde a tela ainda não existe, o passo diz isso e não
deve ser tentado. Nenhum passo foi executado com login real até agora: o resultado esperado vem do código e dos testes
automatizados (`tests/database/run.py`, JUnit/JDBC, vitest e Playwright), não de homologação.

## 0. Antes de começar (uma vez)

| Pré-requisito | Como | Quem |
| --- | --- | --- |
| V053, V054 e V055 aplicadas na homologação | Executor Flyway protegido (docs/EXECUTOR_BANCO.md), com OK do responsável | Operação, com OK |
| API publicada com o código desta entrega | Merge na base → deploy da API na Fly (docs/FLY_API.md) e da web na Vercel | Operação |
| Flags na Vercel Production, copiadas para a Fly (`fly-api inspect → apply → deploy`) | `CP_DEMO_ENABLED=true`, `CP_PARTNERS_ENABLED=true`, `CP_SECURITY_LOG_ENABLED=true`; as já ligadas (`CP_PLATFORM_ENABLED`, `CP_MANAGEMENT_ENABLED`, `CP_RESERVATIONS_ENABLED`, `CP_PARCELS_ENABLED`, `CP_VISITS_ENABLED`, `CP_NOTIFICATIONS_ENABLED`, `CP_ANNOUNCEMENTS_ENABLED`, `CP_REQUESTS_ENABLED`) continuam | Responsável (Vercel) e operação (Fly) |
| Papel `PLATFORM_ADMIN` da sua conta | `ops/sql/platform-admin-grant.sql`, procedimento de [PAINEL_MASTER.md](PAINEL_MASTER.md) | Operação, com OK |
| Segundo fator (TOTP) cadastrado na sua conta | Na primeira área que pedir: "Confirme sua identidade…" → cadastrar o aplicativo autenticador → código de 6 dígitos | Você |
| Contas de teste | Recomendado: 4 a 5 contas reais suas (e-mails próprios), uma por papel: central/gestão, morador, portaria, zeladoria e parceiro. Uma conta pode acumular papéis, mas cada papel em conta separada deixa a troca de contexto mais clara | Você |

Contas sintéticas do demo não têm usuário no Auth: **ninguém entra com elas**. Elas só existem para dar conteúdo
(moradores, portaria, zeladoria, equipe do parceiro). Você testa sempre com as suas contas, vinculadas ao demo por
ações explícitas da central e da gestão (passos 1.4 a 1.7).

Onde a tela pedir segundo fator, digite o código do autenticador. Toda área de equipe (central, gestão, portaria,
zeladoria, painéis de parceiro) exige AAL2; morador não.

## 1. Central administradora — criar o demo e vincular quem testa

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 1.1 | Entre com a conta da central em `/entrar`. Em "Meus acessos", abra **Central administradora** e confirme o segundo fator. | Abas da central; aparece a aba **Condomínio demo** (só com `CP_DEMO_ENABLED=true`). |
| 1.2 | Aba **Condomínio demo** → **Criar condomínio demo**. Escreva um motivo, digite `demo` (minúsculo) na confirmação. | O botão "Criar demo" fica desabilitado: a confirmação exige `DEMO` exatamente. |
| 1.3 | Digite `DEMO` e clique **Criar demo**. | Mensagem "Condomínio demo criado (geração 1). Nenhuma conta real recebeu papel…". O cartão mostra **[DEMO] Residencial Jardim Fictício (geração 1)** com a etiqueta **DEMO**, o parceiro **[DEMO] Vigilância Fictícia Aurora (geração 1)** e as contagens: 2 torres, 32 unidades, 3 áreas comuns com regras de reserva, 9 pessoas sintéticas no condomínio, 3 avisos, 3 encomendas, 2 visitas, 2 ocorrências, 2 pedidos, 6 pessoas sintéticas no parceiro, 4 escalas e 12 eventos de exemplo. |
| 1.4 | Aba **Condomínios**. | O demo aparece com a etiqueta **DEMO** e a origem "Condomínio demo (dados fictícios)". Os condomínios reais continuam iguais. |
| 1.5 | Aba **Auditoria**. | Eventos "Condomínio demo criado: geração 1, 32 unidade(s), dados fictícios", "Parceiro criado (condomínio demo)", "Papel no parceiro concedido (condomínio demo)" (6), "Contrato com parceiro iniciado (condomínio demo)" e "Escala criada (condomínio demo)" (4), todos com você como ator e o seu motivo. |
| 1.6 | **Gestão real do demo**: no cartão do demo, **Abrir ficha** → **Convidar primeiro gestor** com o nome e o e-mail da conta que fará a gestão → copie o link. | O demo não tem gestor (nenhum gestor sintético), então o convite é aceito pela central. Link de uso único `/convite-gestor#token=…`. |
| 1.7 | Abra o link numa janela anônima, entre com a conta da gestão (mesmo e-mail) e conclua o aceite. | A conta passa a ter **Gestão** no demo; o cartão do condomínio em "Meus acessos" mostra **DEMO**. |
| 1.8 | **Parceiro**: volte à aba **Condomínio demo** → "Vincular quem vai testar" → **Papel no parceiro**. Informe o identificador da conta (a sua vem preenchida), papel **Gestor do parceiro**, nome de exibição e motivo → **Conceder papel**. Repita para outra conta com **Gestor líder de segurança** se quiser testar o comando. | "Papel … concedido a …". Aparece "Papel no parceiro concedido" na Auditoria. Sem `CP_PARTNERS_ENABLED`, o formulário é trocado por um aviso. |

O identificador da conta é o `accountId` (o mesmo `sub` do login); a sua própria conta vem preenchida. Para outra
conta, peça o identificador a quem é dono dela (hoje não há tela que mostre o identificador de outra pessoa).

## 2. Gestão (conta do passo 1.7)

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 2.1 | Em "Meus acessos", abra o demo → **Gestão** e confirme o segundo fator. | Cabeçalho com **DEMO** ao lado do nome e a faixa "DEMO Ambiente de demonstração com dados fictícios. Nada aqui é real." em todas as telas do condomínio. |
| 2.2 | **Unidades**. | Torre A (fictícia) e Torre B (fictícia), unidades 101 a 404 em cada torre (32). |
| 2.3 | **Áreas**. | Salão de festas (fictício) com aprovação e taxa de R$ 150,00, Churrasqueira (fictícia) só sexta a domingo, Quadra poliesportiva (fictícia), cada uma com as regras de reserva (horário, antecedência, duração, reservas ativas, cancelamento). |
| 2.4 | **Pessoas** → **Vínculos e convites**. | Vínculos sintéticos (porteiro, zelador, moradores de A-101, A-102, A-201, B-101, B-302 e o proprietário de B-404) e o seu vínculo de gestão. |
| 2.5 | **Pessoas** → **Novo convite**: papel Morador (ou Responsável), unidade **Torre A (fictícia) · 101**, nome e e-mail da conta "morador". Repita para Portaria (equipe sem unidade) e Zeladoria (equipe sem unidade) com as outras contas. Envie o link de cada convite. | Convite pendente com link. Quem recebe abre `/convite`, entra com a própria conta e aceita; o vínculo aparece **Pendente** em Pessoas até você **Aprovar** (equipe e unidade). |
| 2.6 | **Avisos**. | "Boas-vindas ao condomínio demo" e "Manutenção fictícia do elevador da Torre A" publicados; "Rascunho: assembleia fictícia" em rascunho. Publicar o rascunho: ele passa a publicado. |
| 2.7 | **Pedidos**. | "Vazamento na pia (fictício)" de A-101 em triagem, atribuído ao zelador sintético, com uma mensagem da unidade; "Limpeza do corredor (fictício)" de B-302 aberto. |
| 2.8 | Para a zeladoria (passo 5), atribua "Vazamento na pia (fictício)" ao vínculo de zeladoria da sua conta. | O responsável muda; a zeladoria da sua conta passa a ver o pedido. |
| 2.9 | **Reservas**. | Sem reservas no início (o demo não cria reservas); as do morador (passo 3) aparecem aqui para aprovar ou rejeitar (Salão de festas exige aprovação). |
| 2.10 | **Ocorrências**. | "Lâmpada queimada no hall (fictício)" encerrada e "Portão da garagem aberto por tempo excessivo (fictício)" (Segurança) aberta. |
| 2.11 | **Auditoria**. | Ações da gestão feitas por você neste roteiro. |
| 2.12 | Log de Segurança pela gestão. | **Sem tela hoje.** A API (`GET /v1/tenants/{t}/security-log`, `security.log.read.tenant`) existe, mas a web da gestão não tem a seção; o log é lido pelos painéis de parceiro (passo 7). Não tentar pela web. |

## 3. Morador (conta convidada para A-101)

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 3.1 | Entre e abra o demo → **Morador**. | Abre direto a unidade A-101 (única), "Sua unidade, em dia.", com **DEMO** no cabeçalho e a faixa. |
| 3.2 | **Encomendas**. | "Caixa pequena (fictícia)" em "Aguardando retirada", recebida pelo porteiro sintético, no "Armário fictício 1". |
| 3.3 | **Acessos** → "Visitas previstas". | "Visitante Fictício Um" com convite válido a partir de 1 hora depois da criação do demo. **Convidar visitante** cria outro convite (nome fictício). |
| 3.4 | **Reservas**: reserve a Quadra poliesportiva (fictícia) dentro das regras (até 60 min, até 14 dias à frente) e o Salão de festas (fictício). | Quadra confirmada na hora; Salão fica aguardando aprovação da gestão (passo 2.9). |
| 3.5 | Início → avisos e pedidos da unidade. | Os dois avisos publicados; "Vazamento na pia (fictício)" na lista de pedidos da unidade, com a conversa visível à unidade. |
| 3.6 | Tente abrir outra unidade pelo endereço (`/c/{demo}/morador/{id de B-302}`). | Recusado: o morador só vê a própria unidade. |

## 4. Portaria (conta convidada como Portaria)

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 4.1 | Abra o demo → **Portaria** e confirme o segundo fator. | Seções Encomendas, Visitas e Ocorrências, com **DEMO** e a faixa. |
| 4.2 | **Encomendas** → "A retirar". | As 3 encomendas fictícias (A-101, A-201, B-302). Entregue a de A-101 escolhendo a pessoa da unidade: passa para "Retiradas". **Receber encomenda** registra uma nova. |
| 4.3 | **Visitas** → "Visitas previstas". | Os 2 convites fictícios. **Registrar chegada** de "Visitante Fictício Um" para A-101 cria uma chegada na fila; o morador (passo 3) pode autorizar em **Acessos** → "Chegadas na portaria". |
| 4.4 | **Ocorrências**. | As 2 ocorrências fictícias. **Registrar ocorrência** e **Encerrar ocorrência** funcionam e geram `OCCURRENCE_RECORDED`/`OCCURRENCE_RESOLVED` no Log de Segurança (visível no passo 7). |

## 5. Zeladoria (conta convidada como Zeladoria)

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 5.1 | Abra o demo → **Zeladoria** e confirme o segundo fator. | "Pedidos atribuídos a você". Antes do passo 2.8 a lista fica vazia (o pedido de exemplo é do zelador sintético). |
| 5.2 | Depois do passo 2.8, abra "Vazamento na pia (fictício)". | Conversa da unidade; mudar o andamento para "em execução", "aguardando a unidade" ou concluído com resolução. Não muda responsável nem prazo e não encerra. |

## 6. Central — módulos e reset

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 6.1 | **Módulos por condomínio** → escolha o demo → suspenda Encomendas com motivo. | O morador e a portaria do demo deixam de ver Encomendas ("desligado pela administradora"). Religue em seguida. |
| 6.2 | **Condomínio demo** → **Resetar condomínio demo**: leia o aviso, motivo, `DEMO` → **Resetar demo**. | "Demo resetado: geração 2 criada. A geração anterior ficou suspensa (nada foi apagado)…". Em "Gerações anteriores (1)" a geração 1 aparece suspensa. Na Auditoria: "Contrato com parceiro encerrado", "Parceiro suspenso", "Condomínio demo aposentado (reset)" e "Condomínio demo criado: geração 2…". |
| 6.3 | Volte às contas de gestão/morador/portaria/parceiro e atualize "Meus acessos". | O demo da geração 1 sai dos acessos (condomínio suspenso; parceiro suspenso). Para continuar testando na geração 2, refaça os vínculos (1.6 a 1.8 e 2.5). |
| 6.4 | Clique duas vezes rápido em **Resetar demo** ou repita após erro de rede. | Uma única geração nova (mesma chave de operação); a repetição mostra "Esta operação já tinha sido feita…". |

Reset de condomínio real: a tela nunca oferece (só o demo atual vai no pedido) e o servidor recusa com
`DEMO_RESET_REAL_TENANT` qualquer condomínio que não seja o demo ativo — coberto pelos testes automatizados, não há como
provocar pela interface.

## 7. Painéis de parceiro e Log de Segurança (conta do passo 1.8)

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 7.1 | Em "Meus acessos". | Cartão **[DEMO] Vigilância Fictícia Aurora (geração N) – Painel do Parceiro** com **DEMO**. |
| 7.2 | Abra o cartão e confirme o segundo fator. | Painel com Equipe, Escala, Carteira e Log de Segurança; **DEMO** no cabeçalho. Antes da primeira consulta o painel registra a troca de contexto. |
| 7.3 | **Equipe**. | As 6 pessoas sintéticas (gestora, líder, supervisora, dois operadores, técnico) e você. Você não altera o próprio papel. |
| 7.4 | **Escala**. | 4 escalas no demo (supervisora 7 dias; operador 1 nas primeiras 12 h e de 24 h a 36 h; operadora 2 de 12 h a 24 h, contadas da criação). Crie uma escala para uma conta sua com papel de operador (concedido no 1.8): só com escala vigente ela alcança o demo. |
| 7.5 | **Carteira**. | Contrato vigente com o demo (operador de segurança, 365 dias). |
| 7.6 | **Log de Segurança** → escolha o demo. | Eventos de exemplo: `ROLE_GRANTED` dos vínculos, `OCCURRENCE_RECORDED`/`RESOLVED`, `PARTNER_CONTRACT_CHANGED`, `SCHEDULE_ASSIGNED`, `SHIFT_STARTED`, `CHECKLIST_DONE`, `PATROL_CHECKPOINT`, `INCIDENT_OPENED` (com papel Operador remoto e o parceiro), `DOOR_HELD_OPEN`, `DEVICE_OFFLINE`, `DEVICE_ONLINE` (sistema, sem ator), mais `CONTEXT_SWITCHED` da sua entrada e o que você fez nos passos 2 a 5. |
| 7.7 | **Verificar integridade**. | Cadeia íntegra. |
| 7.8 | Com a conta **Gestor líder de segurança**: painel **Comando de Segurança** → Log de Segurança → exportar com motivo (10 a 500 caracteres). | Exportação com manifesto e evento `LOG_EXPORTED` no log. Visão da carteira, revisão de aberturas, políticas e lista de bloqueio aparecem "em breve". |
| 7.9 | Operador remoto / supervisor / técnico. | Console, turno e técnico aparecem "em breve", sem dados (backend de E2/E11 pendente). Sem escala vigente, o operador vê o painel sem condomínio. |

## 8. Troca de contexto

| # | Passo | Resultado esperado |
| --- | --- | --- |
| 8.1 | Com uma conta que tenha o painel do parceiro demo e outro acesso (condomínio ou central): no painel, **Trocar acesso** → abra o condomínio → volte ao painel. | A tela anterior sai e os dados dela saem da memória antes da nova abrir ("Encerrando a visão anterior…"). Ao entrar de novo no painel, novo `CONTEXT_SWITCHED` no log do demo (passo 7.6). |
| 8.2 | Duplo clique ao entrar no painel. | Um único registro (mesma chave da troca). |
| 8.3 | Limite conhecido. | Sair de um painel de segurança direto para um condomínio ou para a central ainda não grava o lado da origem ([LOG_SEGURANCA.md](LOG_SEGURANCA.md)). |

## 9. Isolamento (o que conferir e o que fica com os testes)

- Em todas as telas do demo aparece **DEMO** (cabeçalho, faixa, cartões em "Meus acessos", lista da central). Nos
  condomínios reais, não.
- Os condomínios reais, os vínculos reais e o Log de Segurança dos condomínios reais não mudam com criar ou resetar o
  demo (testado em `demo_checks` do harness PostgreSQL).
- Recusas que a interface não oferece e estão cobertas por testes: marcar condomínio real como demo (nem o dono do
  schema consegue), tirar a marca do demo, usar o prefixo `[DEMO]` em condomínio real, conta sintética em condomínio
  real, contrato entre parceiro demo e condomínio real (e o inverso), reset de condomínio real (`DEMO_RESET_REAL_TENANT`),
  reset sem a capacidade/segundo fator.

## Registro dos testes

Para cada passo, anote data/hora, conta usada, resultado (ok/diferente) e, se diferente, o que apareceu na tela. Os
resultados entram em [STATUS.md](STATUS.md) como homologação com login real só depois de executados.
