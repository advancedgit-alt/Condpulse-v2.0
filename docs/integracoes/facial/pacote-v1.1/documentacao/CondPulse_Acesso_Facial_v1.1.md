# CondPulse

## Integração e homologação de acesso facial

Arquitetura, regras por área e implantação por condomínio

Versão 1.1 | 30 de outubro de 2026 | Responsável pela aprovação técnica MASTER CondPulse

Este manual especifica a integração de controle de acesso do CondPulse com Hikvision, Control iD, Dahua e Intelbras. O objetivo é permitir que cada condomínio configure suas regras de uso sem receber credenciais ou controles capazes de interromper a integração. A liberação depende da identidade, do vínculo, da área, do contexto e das condições vigentes; cadastrar uma face não concede acesso a todos os locais.

O material reúne pesquisa oficial, extensão do pacote CondPulse v1.0, contratos propostos, catálogo de candidatos, plano de testes e dossiê por condomínio. A classificação inicial dos equipamentos é CANDIDATO DOCUMENTADO. A aprovação de bancada e o aceite da instalação exigem evidências próprias. Esta entrega não certifica equipamentos físicos, não executa aberturas e não publica alterações em produção.

# 1 Escopo e situação da documentação

O arquivo CondPulse_Pacote_Completo_v1.0(1).zip, fornecido nesta solicitação, é a base documental desta extensão. Seu README declara documentação e componentes de referência, incluindo banco, API e adaptadores, sem implantação comprovada pelo próprio pacote. Isso não permite concluir que funcionalidades posteriormente desenvolvidas estejam ausentes do repositório ou da produção. O trabalho de implementação começa pela comparação com o código, migrations e contratos realmente vigentes. [BASE01]

Preservar a separação do CondPulse em relação ao app-advanced. O pacote de referência prevê Java/Spring Boot, React/TypeScript/Vite, Expo, PostgreSQL/Supabase, BFF, API e worker. Esta extensão não determina uma nova migração de nuvem, linguagem ou banco. A topologia operacional em uso deve ser inventariada, e decisões mais recentes do projeto prevalecem sobre a infraestrutura histórica descrita no ZIP.

| Entrega | Estado nesta versão | Evidência para avançar |
|---|---|---|
| Pesquisa e seleção de modelos | Documentada | Fontes oficiais identificadas e datadas |
| Regras e responsabilidades | Especificadas | Revisão do produto e aprovação do condomínio |
| Modelo de dados e API | Extensão proposta | Comparação com repositório e testes de contrato |
| Integração de fabricante | Candidata | API licenciada aplicável, firmware e bancada |
| Abertura e bloqueio físico | Não ensaiados | Testes por canal, sentido e cenário de falha |
| Implantação de condomínio real | Dossiê a preencher | Inventário, comissionamento e aceite local |

A seleção cobre modelos tecnicamente relevantes para portarias, áreas comuns, controladoras e parques existentes. Não constitui ranking de vendas. Estoque, preço, garantia, licença e continuidade de suporte dependem de confirmação com fabricante ou canal autorizado. Capacidades publicadas são limites declarados, não resultados de carga medidos pelo CondPulse.

# 2 Decisões obrigatórias do produto

O CondPulse mantém a fonte oficial das permissões de negócio. O hardware ou a plataforma homologada reconhece a credencial e executa uma autorização que já respeita essas permissões. A decisão que pode impedir a entrada deve acontecer antes do acionamento físico. Um evento recebido depois da abertura serve para auditoria e reconciliação, nunca para implementar retroativamente uma restrição de idade.

Cada condomínio possui dados, regras, conectores, credenciais e evidências separados. Um catálogo técnico global pode ser reutilizado, mas listas de pessoas, faces, convites e eventos não são copiadas de um condomínio para outro. Uma conta com vínculos em vários condomínios recebe avaliações independentes em cada contexto.

O reconhecimento facial identifica uma pessoa cadastrada; não determina sua idade. A data ou comprovação etária vem de cadastro validado, com finalidade e acesso limitados. Nenhum LLM, estimador de idade pela imagem ou serviço generativo participa do caminho de abertura.

Homologar a combinação fabricante, SKU, revisão de hardware, região, firmware, API ou SDK, adaptador, modo de operação e topologia. A aprovação também registra capacidades autorizadas e limitações. Uma combinação aprovada para listas e horários locais não recebe automaticamente aprovação para decisão online, acompanhamento, elevadores ou anti-passback distribuído.

O sistema distingue solicitação, aplicação no dispositivo, autorização, acionamento de relé, abertura da porta e passagem. O gestor visualiza o estado verdadeiro e os destinos pendentes. A saída de emergência e os meios físicos de abandono ficam independentes das regras de aplicativo, sob o projeto e comissionamento da instalação.

# 3 Governo dos acessos e separação de responsabilidades

## 3.1 Papéis administrativos

MASTER CondPulse é a autoridade técnica da plataforma. Aprova perfis de fabricante, versões, adaptadores, procedimentos, envelopes de segurança e promoções do ambiente de homologação para os condomínios. Mantém a administração do cofre e das identidades de serviço. Esse papel não implica acesso irrestrito a dados pessoais nem autorização física automática para qualquer porta.

MASTER de implantação do condomínio é um papel técnico com escopo de tenant, concedido pelo MASTER CondPulse. Pode ser exercido pela mesma equipe central, mas sempre com contexto explícito do condomínio. Cadastra equipamentos, vincula canais a pontos físicos, verifica sensores, instala o conector e executa comissionamento utilizando perfis aprovados. Ele usa referências de segredo e operações controladas; a tela não oferece leitura de senhas brutas ou editor genérico de firmware/configuração.

Gestor do condomínio é o responsável pelas regras de uso aprovadas localmente: áreas, horários, categorias, convites, reservas, aprovações e exceções permitidas. Ele pode criar uma área lógica, mas sua vinculação física depende do MASTER de implantação. Alterar o nome Academia não pode alterar o relé associado nem o condomínio do equipamento.

Porteiro e operador de atendimento executam tarefas de turno e decisões previstas na política. Moradores gerenciam as próprias solicitações e convites dentro do vínculo autorizado. Suporte e integradores recebem concessões temporárias, por finalidade e escopo, com expiração e auditoria.

## 3.2 Matriz de responsabilidade

| Operação | MASTER plataforma | MASTER implantação | Gestor | Portaria |
|---|---|---|---|---|
| Aprovar modelo firmware e adaptador | Aprova | Solicita e ensaia | Consulta status | Consulta status |
| Criar identidade técnica do conector | Aprova e emite | Instala no tenant | Sem acesso | Sem acesso |
| Ler senha de dispositivo no painel | Não oferecido | Não oferecido | Sem acesso | Sem acesso |
| Rotacionar credencial pelo cofre | Operação restrita | Solicita ou executa escopo delegado | Sem acesso | Sem acesso |
| Associar relé leitor e sensor | Define limites | Configura e comissiona | Solicita | Consulta nome do local |
| Criar área lógica e horários | Define catálogo de capacidades | Valida vínculo físico | Configura | Consulta |
| Definir idade e visitantes por área | Define limites técnicos | Verifica execução | Aprova e configura regra local | Aplica regra publicada |
| Cadastrar e aprovar pessoas | Suporte justificado | Apenas se delegado | Gerencia no tenant | Conforme função e turno |
| Abertura pontual | Permissão física separada | Apenas em ensaio autorizado | Se política permitir | Se escopo permitir |
| Atualizar firmware e parâmetros críticos | Controla release | Executa procedimento aprovado | Sem acesso | Sem acesso |
| Exportar eventos identificáveis | Finalidade e concessão | Escopo específico | Escopo e justificativa | Restrito ao atendimento |
| Publicar regras com impacto técnico | Aprova exceções técnicas | Valida capacidade | Publica regra compatível | Sem acesso |

## 3.3 Permissões de implementação

Separar permissões como homologation.approve, installation.bind_channel, connector.rotate_secret, area.manage, policy.edit, policy.publish, person.review, credential.revoke, access.request_open e audit.export. O papel MANAGER existente no pacote não recebe permissões técnicas por herança. Não usar um campo amplo is_admin para conceder todas as ações.

O backend valida a permissão em cada endpoint e também nas tarefas assíncronas. Ocultar botões é apenas apresentação. Uma requisição direta do gestor a uma rota de segredos, firmware ou vínculo físico deve ser negada. IDs de tenant e dispositivo não são autoridade por si só. A criação de um novo MASTER requer um fluxo administrativo auditado; ninguém pode atribuir esse papel a si próprio.

MFA e autenticação recente são requisitos de produto para a administração técnica, exportações sensíveis e alterações de maior impacto. Ações extraordinárias exigem justificativa, duração e aprovador distinto quando indicado. Não construir bypass permanente para suporte.

# 4 Reaproveitamento do CondPulse e lacunas da base

O pacote v1.0 já define as tabelas abaixo. A extensão mantém suas responsabilidades e evita criar cadastros paralelos para resolver o mesmo problema. Os nomes citados são do arquivo de referência, e precisam ser reconciliados com a versão atual do repositório antes da implementação. [BASE01]

| Base documentada | Uso preservado | Extensão necessária |
|---|---|---|
| platform.tenants e accounts | Identidade e condomínio | Catálogo global e concessão técnica por tenant |
| cp.memberships e units | Vínculo residencial e unidade | Sujeito de acesso e contexto de autorização |
| cp.amenities e reservations | Área reservável e reserva | Relação opcional com área física e lista de convidados |
| cp.visitors e visit_permits | Visitante e convite com vigência | Destinos permitidos, percurso e aprovação etária |
| cp.connectors | Provedor e secret_ref | Gateway, certificado, perfil aprovado e estado de saúde |
| cp.access_devices | Equipamento modelo firmware | Revisão de hardware, canais, topologia e homologação |
| cp.access_credentials | Face QR cartão e estado desejado | Escopo por sujeito e direitos separados da credencial |
| cp.credential_syncs | Confirmação por dispositivo | Direitos e política efetivamente observados por destino |
| cp.integration_commands | Processamento de integração | Publicação de direitos e classe especial de abertura |
| cp.webhook_inbox e outbox_events | Entrega durável | Contratos versionados e identidade do canal |
| cp.access_events e audit_events | Histórico e auditoria | Tentativa, decisão, ponto físico e força da evidência |
| cp.legal_records e privacy_requests | Finalidade e direitos | Evidência de idade, responsável e exclusão distribuída |
| cp.support_access_grants | Suporte temporário | Escopos técnicos e concessão de instalação |

O campo policy de amenities não substitui um modelo completo de controle de acesso. Portaria, corredor, casa de máquinas e passagem técnica podem existir sem reserva. Da mesma forma, access_devices.direction não descreve sozinho um controlador com vários canais e leitores de entrada/saída.

A base documenta confirmação por equipamento e revogação prioritária. A extensão aplica o mesmo princípio à publicação das regras e à distribuição dos direitos. Não renomear migrations já aplicadas nem reaplicar V001/V002 do ZIP sobre um banco existente. Gerar a próxima migration conforme o mecanismo já adotado pelo repositório.

# 5 Modelo físico de locais e passagens

## 5.1 Entidades do local

Condomínio representa o tenant. Local ou setor organiza a navegação, como Torre A ou Lazer. Área é o espaço com regras, como academia. Ponto de acesso é a passagem física entre duas áreas, como a porta do corredor para a academia. O sentido indica origem e destino naquela passagem. Leitor identifica; controlador decide ou executa; canal físico aciona; sensores produzem evidências.

![Arquitetura de áreas pontos e equipamentos](../diagramas/mapa_areas.png)

Uma área pode ter três entradas, uma saída livre e uma rota acessível. Todas devem constar da planta e da matriz. Duas áreas que compartilham uma única passagem sem barreira posterior não podem receber promessas de bloqueio físico independente. Uma regra no aplicativo não cria uma separação que não existe no prédio.

| Exemplo sintético | Origem e destino | Leitor | Atuador | Consequência |
|---|---|---|---|---|
| AP01 Entrada social | Exterior para hall | Facial de entrada | Controlador canal 1 | Autoriza ingresso ao hall |
| AP02 Academia | Corredor para academia | Facial de área | Controlador canal 2 | Regra própria de categoria e idade |
| AP03 Salão | Corredor para salão | Facial ou QR aprovado | Controlador canal 3 | Depende de reserva e convite |
| AP04 Saída social | Hall para exterior | Sensor ou leitor de saída | Saída física aprovada | Não herda restrição etária de ingresso |
| AP05 Rota acessível | Hall para lazer | Identificador acessível | Canal próprio | Requer as mesmas restrições de ingresso |

## 5.2 Vínculo físico imutável e alterações

Identificar cada canal por tenant, conector, controlador e identificador físico. Não escolher porta pelo nome recebido de um webhook. Validar unicidade para impedir que Academia e Casa de máquinas apontem acidentalmente para o mesmo relé. Renomear um local preserva seu ID e histórico. Trocar relé, sentido ou sensor cria revisão de instalação e exige ensaio do ponto.

O dossiê deve registrar tipo de fechadura, circuito, fonte, nobreak, contato de porta, botoeira, dispositivo de emergência, intertravamento, condição sem energia e responsável técnico. A especificação de software não determina uma ligação elétrica universal. Saídas de alarme não contam automaticamente como portas independentes.

Andar de elevador, garagem, cancela, portão veicular e eclusa são tipos próprios. Homologar uma porta pedestre não aprova esses mecanismos. Placa veicular ou tag pode autorizar veículo, mas não comprova a identidade de cada ocupante.

# 6 Pessoas vínculos e credenciais

Separar sujeito de acesso, conta de login, vínculo, credencial e autorização. Um visitante pode não ter conta no aplicativo. Um morador pode ter várias unidades, e uma pessoa pode possuir mais de um método de identificação. Nenhum método deve contornar as restrições da mesma autorização.

Criar uma referência de sujeito por tenant, vinculada a pessoa visitante ou conta/membership existente. Para preservar vínculos múltiplos, manter relação explícita entre sujeito e contextos válidos. O identificador enviado ao fabricante é opaco e estável no escopo do conector. Não usar CPF, número de apartamento ou ID global de conta como chave universal do hardware.

| Categoria de contexto | Origem da autorização | Limites básicos |
|---|---|---|
| RESIDENT | Vínculo ativo do morador | Áreas autorizadas ao vínculo e regulamento |
| DEPENDENT | Vínculo do dependente | Idade comprovada e condições aplicáveis |
| OWNER_NONRESIDENT | Política específica | Propriedade não implica todas as permissões do ocupante |
| VISITOR | Convite válido | Áreas, horários, anfitrião e destino explícitos |
| SERVICE_PROVIDER | Ordem ou autorização de serviço | Rota, horário e validade limitada |
| EMPLOYEE | Vínculo funcional autorizado | Áreas do turno e função |
| EMERGENCY_RESPONDER | Procedimento operacional específico | Conferência e auditoria; sem credencial genérica permanente |

Uma pessoa cadastrada simultaneamente como visitante e moradora não escolhe livremente o contexto mais permissivo. O motor resolve o contexto que fundamenta a tentativa e avalia restrições de sujeito, área e vínculo. A união de grupos não pode remover uma proibição obrigatória. Distinguir bloqueio global de sujeito e restrição só de um contexto: convite vencido não invalida residência legítima, e vínculo residencial não amplia automaticamente direitos de um convite de evento. Casos ambíguos seguem conferência assistida e deixam motivo auditável.

Estados operacionais de uma credencial devem separar revisão, envio, aplicação parcial, ativa nos destinos indicados, falha, revogação pendente e revogação confirmada. A face cadastrada com sucesso pode continuar sem qualquer direito de abrir portas. A interface apresenta onde ela está válida, e não apenas um selo global de Ativo.

# 7 Motor de regras por área

## 7.1 Condições combinadas

Para ingresso controlado, a decisão requer contexto válido, vínculo vigente, credencial aceita, categoria permitida, condições etárias satisfeitas, autorização vigente, janela de horário, eventual reserva/convite e requisitos específicos da área. As condições obrigatórias são combinadas por E. Alternativas de autenticação, como face OU cartão, não significam face OU regra etária.

No perfil da área, manter defaultEffect DENY para ingresso sem autorização. Esse padrão não interfere na saída física de emergência e não permite suspender arbitrariamente o acesso essencial à residência. Políticas de ingresso essencial precisam de tratamento operacional próprio, aprovado pelo condomínio e revisão aplicável.

| Dimensão | Exemplos configuráveis | Origem do dado |
|---|---|---|
| Quem | Morador visitante prestador | Vínculo ou convite validado |
| Onde | Área e pontos de ingresso | Instalação comissionada |
| Quando | Agenda feriados início fim | Política com fuso do condomínio |
| Idade | Mínimo 18 anos | Evidência cadastral verificada |
| Vínculo com evento | Reserva confirmada | Módulo Reservas |
| Acompanhamento | Atendimento obrigatório ou passagem acompanhada | Operação/sensores homologados |
| Quantidade | Uso único lotação anti-passback | Contador transacional e sensor apropriado |
| Método | Face cartão QR | Credencial e capacidade homologada |
| Offline | ACL limitada ou atendimento | Perfil técnico aprovado |

## 7.2 Ordem determinística da avaliação

1. Resolver tenant, instalação, ponto e sentido pelo canal autenticado; rejeitar inconsistência de contexto.
2. Verificar que a revisão técnica e o modo de execução são aprovados para a regra. Uma função não suportada impede publicação.
3. Resolver o sujeito e o vínculo que autoriza aquela tentativa; verificar validade, revogação e versão.
4. Aplicar bloqueios e limites obrigatórios daquele escopo. Uma exceção de visita não sobrepõe uma interdição de área.
5. Verificar categoria, idade comprovada, horários, convite, reserva e demais condições exigidas.
6. Verificar condições dinâmicas comprováveis, como consumo único, presença ou intertravamento, no componente que possui autoridade consistente.
7. Produzir ALLOW, DENY ou REQUIRE_ASSISTANCE, motivo, versões, prazo da decisão e ponto físico específico.
8. O gateway ou adaptador revalida expiração e versões relevantes imediatamente antes do envio. Um relé ou terminal genérico não conhece versões CondPulse: no modo DEVICE_LOCAL_ACL, a proteção depende da vigência realmente executada pelo firmware e da confirmação de aplicação, permanecendo a janela de sincronização documentada. Evidências físicas atualizam o histórico sem inventar passagem.

Se a avaliação falhar, registrar UNAVAILABLE. REQUIRE_ASSISTANCE e UNAVAILABLE negam atuação automática no hardware binário; orientar atendimento, sem converter falha em ALLOW. O terminal não revela idade ou motivo sensível; detalhes ficam no painel autorizado.

## 7.3 Precedência e exceções

Uma negativa obrigatória do sujeito ou área prevalece sobre concessões comuns no mesmo escopo. A regra da área não pode ampliar limites técnicos da plataforma nem concessões fora do tenant. Exceção temporária exige justificativa, aprovador, início, fim e pontos abrangidos. Nunca usar um grupo VIP que ignore todas as regras. Toda exceção expira e participa da reconciliação.

Políticas globais da plataforma definem o que é tecnicamente configurável. O condomínio define o que deseja permitir dentro desse limite e de seu regulamento. O MASTER marca rotas residenciais essenciais e de saída: o gestor não pode impor 18 anos, biometria obrigatória ou dependência de cobrança ao único caminho de morador. Uma mudança desse tipo exige rota alternativa ou procedimento de continuidade efetivamente ensaiado. Um template global atualizado não altera automaticamente regras locais já aprovadas. A atualização mostra diferenças e exige aprovação do responsável pela política local, quando houver impacto de negócio.

# 8 Regras etárias dependentes e acompanhamento

Para uma área definida como 18 anos ou mais, o motor calcula anos completos na data civil do fuso do condomínio. Considerar véspera, aniversário, ano bissexto, correção do cadastro e mudanças de fuso. O critério para nascimento em 29 de fevereiro deve ser registrado pelo responsável pela regra e validado na implementação; não deixar o comportamento implícito em uma biblioteca.

Data ausente, autodeclarada sem a comprovação exigida ou inconsistente não vira maioridade presumida. O resultado é bloqueio de ingresso automático ou conferência assistida, conforme a área. Pode-se conservar uma atestação verificada de maioridade quando suficiente, em vez de distribuir data completa. Para regras com diferentes faixas e futuras mudanças, definir a necessidade de guardar data protegida e a validade da evidência.

O equipamento recebe o direito e a vigência necessários, não a data de nascimento por padrão. Um job agenda a reavaliação da elegibilidade; a permissão só aparece ativa depois da aplicação no destino. A verificação durante a tentativa continua necessária se a condição não puder ser representada por validade local segura.

Menores não recebem cadastro biométrico automaticamente pela aprovação do vínculo residencial. O pacote de referência já restringe o facial de menores até análise específica. Esta extensão preserva esse gate por tenant, finalidade e processo de responsável legal. A configuração de faixa etária é independente da autorização para tratar biometria. [BASE01, LG01, LG02]

| Situação sintética | Regra | Resultado esperado |
|---|---|---|
| Morador com 17 anos e 364 dias | Área 18 mais | Negar ingresso automático |
| Morador com 18 anos completos e evidência válida | Área 18 mais e morador | Permitir se todas as demais condições passarem |
| Visitante com 40 anos | Área apenas moradores | Negar; idade não substitui categoria |
| Pessoa sem evidência de idade | Área 18 mais | Atendimento ou negação conforme regra |
| Dependente acompanhado | Mínimo absoluto de 18 anos | Negar; acompanhante não remove mínimo absoluto |
| Dependente em área que permite acompanhamento | Responsável e fluxo aprovados | Permitir apenas no procedimento homologado |

Há dois tipos distintos de acompanhamento: conferência humana e controle automático de passagem conjunta. Duas faces autenticadas em sequência não demonstram que as pessoas permaneceram juntas. Se a área exige acompanhamento durante o uso, isso depende de operação local e eventualmente sensores; não prometer fiscalização contínua pela simples integração facial.

# 9 Visitantes reservas e circulação

O convite é uma concessão limitada, associada a anfitrião, unidade, visitante, destinos e vigência. Definir se exige aprovação da portaria ou gestor, se permite recorrência, qual método identifica e se o uso é único. Link de convite não é uma senha universal de abertura. O responsável pode cancelar; o cancelamento revoga todos os direitos derivados e mostra pendências físicas.

Uma regra somente visitantes precisa nomear uma passagem ou área destinada a esse público, como recepção externa, e preservar os percursos necessários para moradores. Uma regra sem visitantes impede a liberação por qualquer credencial da visita, inclusive face, cartão ou QR. A permissão de entrar no condomínio não concede uso da academia, piscina ou salão.

O destino inclui um percurso autorizado. Um visitante do salão pode precisar passar pelo hall e corredor; essas autorizações são calculadas de forma explícita, com vigências compatíveis. O percurso não concede áreas vizinhas. Se o prédio não possui barreiras físicas entre ambientes, documentar o limite e prever controle operacional.

## 9.1 Reserva com acesso físico

Reserva aprovada gera elegibilidade para responsável e lista de convidados, observando tolerâncias de entrada e saída definidas na política. Reserva pendente, cancelada ou sem anfitrião válido não gera direito ativo. A alteração de horário recalcula todos os destinos. Cancelar a reserva depois do cadastro facial não deixa a face com permissão geral no local.

Para um salão reservado até 22h, o direito de ingresso pode terminar às 22h, mas a saída física continua possível. Buffers de limpeza não ampliam automaticamente o direito do convidado. Funcionários da limpeza usam concessão própria. A transição entre dois eventos deve evitar sobreposição de direitos quando a regra exigir exclusividade.

## 9.2 Uso único e concorrência

Definir precisamente se uso único se consome na autorização, no comando ou na passagem. O padrão de produto recomendado é reservar o consumo atomicamente antes da abertura e confirmá-lo com a evidência disponível. Se não houver prova de passagem, a política explicita o consumo pela liberação e o operador resolve a exceção; não reabrir automaticamente porque o sensor ficou inconclusivo.

O mesmo convite não pode ser consumido simultaneamente em duas entradas. Isso exige autoridade local única ou coordenação forte no caminho de decisão. Sem essa capacidade, proibir uso único entre múltiplas portas no modo offline. Não vender como controle exato uma deduplicação executada depois das duas aberturas.

## 9.3 Biblioteca inicial de políticas

| Área sintética | Público | Condições | Contingência proposta |
|---|---|---|---|
| Entrada residencial | Morador e visita autorizada | Vínculo ou convite vigente | Continuidade local conforme perfil e atendimento |
| Academia | Morador | 18 mais e agenda 06h às 22h | Apenas direitos locais válidos e limitados |
| Piscina | Morador e visitante autorizado | Limite de convidados e agenda | Atendimento se controle de quantidade indisponível |
| Salão de festas | Responsável e convidados | Reserva aprovada e janela | Somente vigência local comprovada |
| Recepção de visitantes | Visitante | Convite e destino válidos | Conferência assistida |
| Coworking | Morador | Reserva e capacidade | Bloqueio automático se requisito não executável |
| Área infantil | Dependente e responsável | Faixa e acompanhamento aprovados | Operação assistida |
| Casa de máquinas | Funcionário ou prestador | Ordem de serviço e autorização específica | Procedimento técnico local |
| Garagem | Morador ou veículo autorizado | Rota e vagas conforme módulo próprio | Homologação veicular separada |

Estes são exemplos de configuração, não regulamento aprovado de qualquer condomínio. A idade da academia foi escolhida para exemplificar o pedido; o gestor deve aprovar a regra real e seu fundamento.

# 10 Publicação das regras e confirmação física

O gestor edita uma revisão em DRAFT. A validação verifica conflitos, público impactado, requisitos de hardware, limites de grupos/horários, foto disponível quando necessária e todas as passagens de ingresso. A simulação mostra exemplos permitidos e negados, sem comandar portas. A revisão segue aprovação de negócio e validação técnica quando ultrapassar o envelope já comissionado.

Uma publicação aceita retorna um job. O estado passa por VALIDATED, APPROVED, PUBLISHING e CONFIRMED, ou por PARTIAL/FAILED. Cada destino registra versão desejada, versão observada, hash da autorização, instante e método de confirmação. APPLIED_UNVERIFIED informa que o provedor aceitou uma configuração sem prova suficiente de aplicação; não é sinônimo de CONFIRMED.

Uma revisão restritiva deve priorizar a retirada de direitos anteriores. Uma ampliação só se ativa após as condições estarem aplicadas. Se o fabricante não oferecer transação atômica, o compilador usa sequência segura e documenta o intervalo de indisponibilidade. Não misturar direitos de versões incompatíveis para conseguir um status verde.

Não há garantia universal de publicação atômica entre todos os dispositivos. Em regra crítica, se algum destino não consegue aplicar a restrição, a implantação permanece bloqueada. Colocar o ingresso em atendimento requer ação física/local confirmada que impeça a ACL antiga de abrir indevidamente; mudar o status no painel não desarma um leitor desconectado. A interface mostra os riscos residuais e quem assumiu a operação local.

Um rollback é uma nova revisão auditada. Ele não pode reativar pessoas revogadas, convites vencidos ou condições de segurança retiradas desde a versão anterior. Recompilar a revisão anterior sobre o estado atual dos vínculos e das revogações; não simplesmente restaurar uma lista histórica de usuários.

# 11 Operação local falhas e limites de revogação

Separar queda da internet, indisponibilidade do backend, falha do gateway, perda de comunicação com o leitor, falha do controlador e falta de energia. Cada uma atinge uma etapa diferente. O dossiê registra a resposta por ponto de acesso e por classe de usuário.

| Modo aprovado | Onde decide | Regras possíveis | Condição de aprovação |
|---|---|---|---|
| DEVICE_LOCAL_ACL | Terminal ou controlador | Direitos e horários representáveis localmente | Vigência e revogação ensaiadas no firmware |
| EDGE_ONLINE | Gateway local antes do relé | Regras dinâmicas compatíveis | Callback pré-relé e fallback seguro comprovados |
| PROVIDER_LOCAL | Plataforma local homologada | Conforme contrato e capacidade | Confirmação por destino e responsabilidade definida |
| ASSISTED | Operador autorizado | Conferência operacional | Procedimento, escopo e registro de decisão |

No Control iD, a documentação descreve modo Pro com identificação local e decisão no servidor, além de contingência baseada nos registros locais após falhas de comunicação. No SS 3532/3542 MF W, a documentação Intelbras também prevê decisão offline usando o cadastro local quando a resposta remota excede o limite. Portanto, optar por modo online não elimina a obrigação de restringir a base local. [CI07, IB19]

Uma ACL offline precisa de validade executável no próprio componente que abre a porta. Um prazo no backend não expira um direito permanente que ficou no leitor desconectado. O gateway também não consegue garantir revogação se o dispositivo autônomo permanecer abrindo com uma lista ampla e sem validade. Nessas combinações, a área que exige expiração não recebe aprovação para esse modo.

Definir limite máximo de idade da política, desvio de relógio e tempo máximo de risco aceitável por área. Valores iniciais são hipóteses de engenharia a medir, não compromissos de fornecedor. A pior janela de revogação depende da reconexão efetiva ou da expiração local: sem ambas, a pendência pode durar indefinidamente. Mostrar esse fato no painel.

Para ingresso essencial de morador, definir continuidade e atendimento local sem depender de nuvem ou cobrança do SaaS. Para área restrita temporária, adotar autorização local curta e verificável ou atendimento. A falha não deve ampliar uma lista de autorizados. Saída e abandono seguem o circuito físico aprovado mesmo quando a entrada automática é negada.

# 12 Arquitetura da integração

![Arquitetura de integração do CondPulse](../diagramas/arquitetura_acesso.png)

O BFF/API existente autentica a sessão e o contexto do tenant. O módulo de acesso consulta os vínculos, convites, reservas, evidências e políticas no PostgreSQL. O motor produz direitos e decisões; o worker publica o estado desejado usando outbox e reconciliação. O conector local mantém comunicação privada com equipamentos e conexão de saída autenticada para a plataforma.

O gateway deve ser a opção padrão proposta quando há dispositivos em LAN. Ele reduz dependência de latência de internet para decisões compatíveis e evita abrir a interface administrativa do leitor para a internet. A comunicação nuvem-gateway usa identidade individual e escopo fixo de condomínio, com rotação e revogação. Não presumir que o equipamento ofereça mTLS: onde a última conexão usa HTTP local, documentar esse limite, segregar a rede e aplicar controles locais.

O gateway não possui acesso direto de escrita ao banco central e não é um proxy de comandos arbitrários. Ele aceita operações tipadas, destinos previamente cadastrados e versões aprovadas. Qualquer imagem temporária é obtida por um fluxo interno autenticado e limitado ao destino; não usar URLs públicas de biometria. O frontend nunca recebe senha, sessão do fabricante ou chave de serviço.

O modo local pode manter uma cópia mínima dos direitos necessários, cifrada e com prazo. Registra eventos em fila durável, aplica limites de disco e prioriza revogações. Contadores que exigem unicidade precisam de uma autoridade ativa por instalação. Um gateway reserva só assume após eleição ou bloqueio que impeça dois controladores de decisão simultâneos.

Adaptadores diretos e plataformas intermediárias são alternativas com contratos distintos. HikCentral, DSS ou software de parceiro não são dependências universais. Se já existirem no condomínio, definir qual sistema é o único responsável por escrever pessoas e direitos. Nunca integrar lendo ou alterando diretamente o banco privado de um software de fabricante sem contrato suportado.

Na organização Java proposta pelo pacote, manter módulos de areas, access-policy, access-identity, device-integration e access-audit, com portas de domínio e adaptadores. SDK nativo que exija outro processo pode ser encapsulado em serviço auxiliar local, com protocolo estreito, versão e distribuição licenciada. Não alterar a linguagem do backend apenas pela existência de um SDK de fabricante.

# 13 Extensão do modelo de dados

## 13.1 Estrutura e isolamento

Toda entidade de negócio usa tenant_id. Relações entre registros de condomínio usam chaves compostas que incluem esse identificador. Um ID válido de outro tenant nunca deve completar uma relação. O catálogo global contém somente metadados de modelos, versões e homologações técnicas; não contém faces ou cadastros de moradores.

RLS protege o isolamento no banco, complementada pela autorização de negócio do backend. Papéis de aplicação não devem ser owners, superusers ou possuir BYPASSRLS. Configurar contexto de tenant e ator na mesma transação e conexão das consultas, sem estado persistente reutilizado por outro tenant no pool. Não usar dados de user_metadata modificáveis pelo usuário como fonte de permissão. [DB01, DB02]

Os nomes de tabelas a seguir são propostas de extensão. O arquivo dados/modelo_logico.json detalha campos, invariantes e índices. A implementação deve reaproveitar equivalentes já existentes e produzir migrations incrementais, sem aplicação direta deste desenho à produção.

| Entidade proposta | Finalidade | Campos e restrições centrais |
|---|---|---|
| platform.access_model_profiles | Catálogo técnico | Fabricante SKU hardware região referência de documentos |
| platform.access_homologations | Aprovação de combinação | Perfil firmware adaptador modo capacidades evidências status aprovador |
| cp.technical_grants | Papel técnico no tenant | Conta escopos início fim aprovador revogação |
| cp.access_areas | Espaço com regra | Nome setor tipo rota essencial status amenity_id opcional |
| cp.access_points | Passagem entre áreas | Origem destino tipo sentido rota acessível estado |
| cp.device_channels | Canal físico de equipamento | Device_id external_channel tipo e limites; unicidade por destino |
| cp.point_bindings | Revisão de instalação | Ponto canal leitor sentido sensores perfil homologado revisão |
| cp.edge_agents | Conector local | Identidade técnica public key referência de certificado estado versão |
| cp.access_subjects | Pessoa no contexto tenant | Conta ou visitante exatamente uma origem; estado e versão |
| cp.subject_contexts | Vínculos válidos do sujeito | Membership ou permit ou autorização funcional; validade |
| cp.age_evidence | Evidência de idade | Sujeito método verificador instante atestação ou data protegida |
| cp.access_policies | Regra lógica da área | Área revisão corrente responsável e envelope permitido |
| cp.access_policy_versions | Conteúdo imutável de regra | Documento validado hash autoria aprovação vigência |
| cp.access_grants | Direito derivado | Sujeito contexto área origem validade estado e versão |
| cp.policy_deployments | Publicação de revisão | Política destinos estado resumo e operation_id |
| cp.entitlement_syncs | Direito observado por destino | Grant device channel revisão desejada observada evidência |
| cp.access_decisions | Decisão sobre tentativa | Attempt_id sujeito ponto versões resultado motivos validade origem |
| cp.access_command_receipts | Abertura sem repetição | Command_id ponto nonce prazo envio estado evidência |
| cp.revocation_markers | Marca durável de revogação | Sujeito ou grant escopo versão motivo instante |
| cp.installation_acceptances | Aceite local | Revisão de planta testes aprovadores técnico e regras |

## 13.2 Invariantes obrigatórias

Uma política publicada é imutável. Edições criam nova versão; hashes permitem detectar mudança de conteúdo. Um direito deriva de vínculo, convite, reserva ou exceção identificável, nunca apenas da existência de face. A expiração do direito não supera a de sua origem. A revogação de uma origem invalida suas concessões descendentes.

Um canal físico possui no máximo um vínculo ativo de atuação no mesmo contexto operacional. Comandos apontam para binding_revision e ponto autorizados. Alterar o mapeamento de porta invalida comandos anteriores e exige nova confirmação. O estado do equipamento deve ser confrontado com seu perfil homologado ao reconectar.

O modelo de access_credentials do ZIP aceita account_id ou visitor_id. A migração para sujeito de acesso pode manter esses campos inicialmente como compatibilidade. Fazer backfill idempotente, validar correspondências e migrar consumidores antes de remover redundância. Não deixar duas fontes editáveis divergentes para pessoa/tenant.

Para idade, manter dados restritos fora das projeções de portaria e dispositivos. Quando houver data de nascimento, aplicar proteção de campo e permitir acesso apenas ao serviço/verificador apropriado. O token de interface não carrega a data. O direito gerado pode conter ageRequirementSatisfied com proveniência interna e expiração, sem constituir uma credencial reutilizável fora do contexto.

## 13.3 Leitura para decisão e concorrência

A leitura deve resolver tenant, sujeito, vínculo/convite, grant, política publicada e binding ativo sob uma visão consistente. Exigir versão ou marcador de alteração para detectar modificação relevante durante a avaliação. Em ações sensíveis, reavaliar antes do envio. Isso reduz a janela de corrida, mas não cria atomicidade com um relé remoto; medir e registrar o intervalo residual.

Não fazer chamada de fabricante dentro de transação longa do banco. Gravar mudança e outbox na mesma transação; o worker executa depois, com idempotência e verificação do estado mais recente. Revogações entram em fila prioritária e não ficam atrás de importações de milhares de fotos.

Índices propostos: tenant e estado de vínculos; tenant sujeito validade de grants; tenant área revisão de políticas; tenant device channel; tenant dispositivo época e ID do evento; tenant estado next_attempt_at para jobs. Validar planos reais e limites de cardinalidade. Eventos podem ser particionados por período após medição, mantendo deduplicação consistente com a estratégia escolhida.

## 13.4 Migração e compatibilidade

Primeiro inventariar schema, migrations, roles e contratos do repositório atual. Depois criar estruturas aditivas e preencher os vínculos sem ativar portas. Executar testes com dois tenants e IDs de fabricante iguais. A seguir habilitar consultas e telas em flag de homologação, reconciliar políticas e finalmente habilitar escrita externa para o piloto.

O rollback da aplicação preserva as novas marcas de revogação e evidências. Não executar DROP de dados necessários para demonstrar uma restrição já aplicada. Uma migration corretiva e um procedimento de compatibilidade são preferíveis a apagar histórico. O ZIP acompanha modelo lógico e contratos, não uma migration pronta para executar sobre o banco atual.

# 14 Contratos HTTP do CondPulse

Os contratos neste capítulo são rotas propostas do CondPulse. Não representam URLs de Hikvision, Control iD, Dahua ou Intelbras. O arquivo contratos/openapi_acesso_proposto.yaml é um delta a integrar com o OpenAPI existente depois de reconciliar autenticação, envelope de erro e versionamento.

Preservar as rotas documentadas de credentials, permits, access-devices, access-events, operations e webhooks quando já implementadas. Acrescentar capacidades com compatibilidade, sem criar dois serviços que discordam sobre revogação. [BASE01]

| Grupo de rotas propostas | Operações | Autoridade |
|---|---|---|
| /v1/platform/access-models | Consultar catálogo | Administração técnica |
| /v1/platform/access-homologations | Criar dossiê e decidir aprovação | MASTER plataforma |
| /v1/tenants/{tenantId}/access-areas | Criar consultar editar área lógica | Gestor autorizado |
| /v1/tenants/{tenantId}/access-points | Definir passagem e consultar mapa | MASTER implantação; consulta operacional limitada |
| /v1/tenants/{tenantId}/point-bindings | Vincular canal e revisão física | MASTER implantação |
| /v1/tenants/{tenantId}/access-policies | Criar revisões e consultar | Gestor |
| /access-policies/{policyId}/validate | Compatibilidade e conflitos | Gestor; sem efeito no hardware |
| /access-policies/{policyId}/simulate | Explicar decisão hipotética | Gestor no escopo permitido |
| /access-policies/{policyId}/publish | Publicação versionada | Gestor dentro do envelope aprovado |
| /access-deployments/{deploymentId} | Confirmar destinos e pendências | Gestor e MASTER conforme projeção |
| /age-evidence | Registrar verificação | Verificador autorizado |
| /access-points/{pointId}/open-requests | Solicitar abertura pontual | Permissão física separada |
| /installations/{installationId}/commission | Registrar comissionamento | MASTER implantação |
| /connectors/{connectorId}/rotate-secret | Iniciar rotação controlada | MASTER com escopo técnico |
| /v1/edge/events e /v1/edge/decisions | Canal autenticado do gateway | Identidade de máquina restrita |

Rotas abreviadas da tabela pertencem ao mesmo prefixo de tenant. O contrato completo contém os caminhos integrais. Nenhuma operação oferece retorno de senha ou token do fabricante. O campo secret_ref é interno e também não aparece nas projeções comuns do gestor.

## 14.1 Semântica de requisições

Mutações administrativas usam controle de versão, idempotência quando aplicável e autorização por escopo. Idempotency-Key repetida com payload diferente retorna conflito. Publicação retorna 202 e operationId; não comunica abertura nem sucesso em todos os terminais. PATCH de rascunho usa versão esperada, evitando sobrescrever o trabalho de outro gestor.

Na sessão de navegador com cookie, exigir proteção CSRF para escrita e validação de origem. Aplicativos e integrações autenticadas usam o mecanismo já aprovado pelo backend. Um JWT válido não elimina a verificação de vínculo atual ou revogação. O contexto do tenant é resolvido e validado no servidor, mesmo quando aparece no caminho.

Use 401 para autenticação ausente/inválida, 403 para falta de permissão, 404 sem revelar recurso de outro tenant, 409 para conflito de versão/estado, 422 para regra incompatível, 429 para limite e 503 para indisponibilidade. Um timeout após envio ao fabricante produz estado UNKNOWN no job; não converter automaticamente em operação nunca enviada.

## 14.2 Simulação e explicação

Uma simulação retorna decisão, motivos, política/revisão, fatos considerados e capacidades faltantes. Deve operar sobre dados sintéticos ou sujeitos que o ator esteja autorizado a consultar. Não permitir simulação como meio de enumerar idade, presença ou histórico de qualquer morador. O painel mostra, por exemplo, Visitantes não têm acesso a esta área, sem exibir a data de nascimento.

# 15 Contrato dos adaptadores e capacidades

AccessProviderPort permanece a fronteira de domínio. A implementação transforma operações internas em chamadas oficiais do fabricante. Cada adaptador é versionado, declara modelos e firmwares testados e falha de forma explícita quando não consegue representar uma regra. Uma lista de protocolos na ficha não basta para implementar todas as operações.

| Operação interna | Entrada principal | Resultado exigido |
|---|---|---|
| discoverCapabilities | Destino já autorizado | Identificação firmware limites e funções observáveis |
| upsertPerson | Referência opaca e versão | Pessoa no destino ou falha individual |
| enrollFace | Referência de captura autorizada | Aceite rejeição qualidade e aplicação observável |
| applyEntitlements | Direitos por canal e vigência | Confirmação por destino e limitações |
| readAppliedState | Pessoa direito e destinos | Estado observado e força da evidência |
| revokeEntitlements | Escopo e versão de revogação | Pendências até negação confirmável |
| deleteBiometrics | Sujeito e destinos específicos | Exclusão por destino sem remoção global acidental |
| subscribeEvents | Cursor ou canal autorizado | Eventos normalizados e continuidade |
| backfillEvents | Janela limitada por dispositivo | Histórico paginado sem duplicar |
| requestOpen | Comando ponto prazo e motivo | Rejeitado enviado confirmado ou desconhecido |
| readHealth | Equipamento e conector | Rede relógio versão fila capacidade e alarmes |

Cada capacidade possui três campos separados: documentada pelo fabricante, observada na bancada e aprovada pelo MASTER. Booleano unknown não pode virar true por valor padrão. Exemplos: faceEnrollment, faceDeletion, perDoorRights, localValidity, onlineDecisionBeforeRelay, offlineRestriction, doorContactEvents, passageEvents, scopedRemoteOpen, eventBackfill, secureTransport e safeRecovery.

Limites também fazem parte do contrato: faces, usuários, imagens por pessoa, cartões, grupos, planos, faixas de horário, validade, tamanho de foto, concorrência, taxa, sessões e armazenamento de eventos. Uma instalação pode caber em número de faces e exceder o número de planos. O compilador informa a dimensão excedida e impede truncamento silencioso.

## 15.1 Credenciais e transporte de fabricante

Aplicar autenticação oficialmente suportada, preferencialmente com proteção de transporte. Digest não cifra o conteúdo da foto e não substitui HTTPS. Quando equipamento não autentica push com assinatura suficiente, receber dentro da rede segregada, vincular origem ao inventário e encapsular no gateway autenticado; não declarar que a mensagem original tem assinatura que ela não possui.

Não disponibilizar uma rota de proxy com URL/método/corpo arbitrários para o gestor. Limitar destinos do conector e evitar requisições a redes administrativas não cadastradas. Material de SDK, licenças e pacotes proprietários deve ser obtido pelo canal autorizado; esta entrega referencia as fontes, sem redistribuí-los.

## 15.2 Interoperabilidade física

Wiegand, OSDP e RS-485 não são equivalentes a uma API de cadastro facial. RS-485 descreve uma interface física que pode transportar protocolo proprietário. OSDP declarado não comprova Secure Channel ativo. Validar os papéis de leitor/controlador, direção, endereçamento, limites e proteção efetivamente habilitada.

Ao usar identificadores numéricos entre leitor e controlador, validar largura e formato, e impedir colisão após truncamento. O template biométrico permanece no componente que reconhece; não presumir portabilidade de vetores entre marcas. Um controlador de outra marca, mesmo aceitando a identificação, precisa da própria aprovação para decisão e atuação.

# 16 Eventos evidência e abertura remota

## 16.1 Eventos com significados distintos

| Evento normalizado proposto | O que demonstra | O que não demonstra |
|---|---|---|
| IDENTITY_RECOGNIZED | Método identificou referência | Permissão para entrar |
| ACCESS_ALLOWED | Política autorizou tentativa | Relé acionado |
| ACCESS_DENIED | Tentativa negada no ponto de decisão | Ausência de outro caminho físico |
| OPEN_COMMAND_ACCEPTED | Comando recebido | Abertura física |
| RELAY_TRIGGERED | Atuador informou acionamento | Porta aberta ou pessoa passou |
| DOOR_OPENED | Contato indica porta aberta | Quem passou e quantas pessoas |
| PASSAGE_DETECTED | Sensor apropriado registrou passagem | Identidade se não houver correlação confiável |
| DOOR_FORCED ou DOOR_HELD | Alarme conforme fonte homologada | Causa ou culpado sem apuração |
| ENTITLEMENT_APPLIED | Direito observado no destino | Aplicação em todos os demais |
| CREDENTIAL_REVOKED | Revogação confirmada no destino | Exclusão de backups ou outros destinos |

O enum access_events do pacote possui categorias mais agregadas. Criar mapeamento de compatibilidade sem perder o detalhe original em evidence e kind. Um evento cuja direção é desconhecida permanece UNKNOWN. Não transformá-lo em saída para corrigir ocupação aparente.

Cada envelope carrega schemaVersion, eventId, connectorId, deviceId, pointId quando resolvido, kind, occurredAt, receivedAt, direction, evidenceLevel, correlationId e referências opacas. Quando política ou versão não for informada pelo equipamento, usar null; uma associação inferida pela plataforma recebe marca explícita de inferência.

A chave de deduplicação usa tenant, identidade estável da instância provisionada do equipamento e identificador/época nativa do evento, conforme o contrato homologado. O conector registra a proveniência da entrega, sem tornar novo um evento já recebido por outro gateway. Se IDs nativos reiniciarem, acrescentar época de boot ou outra composição ensaiada. Não deduplicar somente por pessoa e segundo: duas tentativas legítimas podem ocorrer no intervalo. Preservar origem, sequência e atraso; o horário de recepção não substitui o de ocorrência.

Resolver ponto e sentido pelo vínculo físico válido no momento da ocorrência, preservando bindingRevision quando comprovável. Evento antigo recebido após remapeamento de canal não pertence automaticamente à porta atual. Quando não houver elementos confiáveis para essa associação, registrar ponto desconhecido ou inferência explícita, sem atualizar presença, lotação ou anti-passback com base no vínculo atual por conveniência.

## 16.2 Abertura pontual com validade curta

Uma abertura solicitada no sistema requer permissão física, ponto comissionado, motivo, escopo e condições atuais. Para portaria, associar chegada/atendimento quando pertinente. Para morador, manter o recurso desabilitado até que o perfil e a política local o aprovem; isso preserva o gate já previsto na base. Não acionar uma porta real ao testar a função de simulação.

Gerar commandId, nonce, installationRevision, pointId, issuedAt, expiresAt, actorId, authorizationRef e policyVersion. O prazo inicial de referência é até 10 segundos, a reduzir conforme a capacidade e o ensaio. O gateway valida prazo, identidade, destino, unicidade e contexto antes do envio. O protocolo do dispositivo pode não suportar todas essas propriedades; o certificado de homologação deve registrar a proteção efetivamente obtida.

Persistir recibos e auditoria é necessário; reexecutar abertura antiga é proibido. A fila de integração pode registrar o comando, mas o despachante nunca o reproduz após expiração, falha ambígua ou reconexão. O gateway que efetivamente transmite mantém registro durável por commandId, tomada atômica e serialização. O compromisso de envio não é desfeito nem reatribuído após crash ou failover. Se houver falha entre esse registro e a chamada, aceitar possível perda do comando e requerer nova solicitação consciente; não preferir uma segunda abertura oculta.

Um lease de worker isolado não impede um gateway antigo de retomar e transmitir em hardware sem fencing. Não reatribuir comandos já comprometidos; controlar quem pode efetivamente transmitir na instalação. Desativar retries implícitos do cliente HTTP, SDK, proxy e filas para abertura sem idempotência nativa comprovada. A garantia possível e a janela residual precisam constar do ensaio.

Se o dispositivo não possui idempotência nem consulta conclusiva, timeout vira UNKNOWN e não há retry automático. O operador verifica a situação e decide uma nova solicitação com novo contexto. Não alegar garantia de execução exatamente uma vez sobre um protocolo que não a oferece.

# 17 Cadastro facial sincronização e revogação

## 17.1 Cadastro seguro

Validar o vínculo e a finalidade antes da captura. A coleta informa destino e uso, oferece alternativa autorizada e registra o aviso aplicável. A foto é conferida por formato, tamanho, qualidade, presença de uma face e requisitos do fabricante. Remover metadados desnecessários e impedir envio de arquivos inesperados. As verificações de imagem não substituem prova de identidade ou de vida.

Primeiro resolver o sujeito e criar a pessoa de forma idempotente. Aplicar estado sem acesso amplo; confirmar que o firmware não concede grupo padrão permissivo nessa etapa. Enviar a imagem e verificar resposta por indivíduo. Só então publicar direitos válidos e confirmar cada destino. Falha parcial não habilita outros métodos de acesso sem a política correspondente.

Enviar a face apenas para destinos necessários ao escopo aprovado. Quando for preciso compartilhar uma captura entre dispositivos autorizados, usar trânsito protegido e prazo curto. Se uma nova porta for habilitada depois de a foto transitória ter sido apagada, recapturar ou usar um mecanismo autorizado de origem, sem exportar biometria silenciosamente de outro condomínio.

## 17.2 Idempotência e reconciliação

Cada envio leva referência estável, versão desejada e hash de conteúdo não reversível quando adequado. Um retorno antigo nunca reduz a versão de uma revogação mais recente. A reconciliação consulta o estado final e compara com a intenção atual; não reproduz cegamente todas as mensagens históricas.

Importações em massa têm prévia, limite de concorrência e janela operacional. Um lote não altera o destino de tenant pelo conteúdo do CSV. Registrar erros individualmente e evitar rollback global que remova pessoas válidas. Limites de capacidade devem impedir a carga antes de esgotar o equipamento, reservando margem operacional definida no piloto.

## 17.3 Bloqueio e exclusão

Bloquear direito de entrada e excluir biometria são operações distintas. Uma mudança de residência ou revogação de convite retira imediatamente a elegibilidade no backend, grava marcador durável e dispara retirada prioritária nos destinos. A tela informa restrição solicitada e os locais ainda pendentes. O contato humano/local é acionado quando a urgência não comporta a janela offline.

Não basta apagar a face: cartão, PIN, QR, controle remoto ou concessão em plataforma intermediária podem continuar autorizando. A retirada deve alcançar todos os direitos derivados do mesmo contexto. A exclusão da biometria segue a finalidade e o processo de retenção, com comprovação individual, sem confundir bloqueio lógico com apagamento físico.

Depois de restaurar backup, recuperar revogações posteriores ao snapshot a partir de journal, WAL preservado ou outra fonte autoritativa comprovada, identificando o ponto de recuperação. Sem essa evidência, direitos restaurados não são presumidos vigentes. Reativar atuação somente após reconciliação e confirmação do comportamento físico aplicável; bloquear apenas escrita não impede uma ACL antiga de abrir portas. Equipamento substituído recebe apenas direitos vigentes. Antes de mover um terminal entre condomínios, limpar dados, revogar identidades técnicas, confirmar ausência de registros anteriores e executar novo comissionamento. Trocar tenant_id não sanitiza o aparelho.

# 18 Segurança técnica e administração de segredos

Segredos ficam em cofre gerenciado pela plataforma, com identidade de serviço restrita e rotação. Se o ambiente CondPulse adotar Infisical, usar projeto/ambiente próprio e concessões por conector; a existência de Infisical na infraestrutura Advanced não comprova configuração no CondPulse. O banco guarda referências e estado de rotação, não senha em JSON de configuração.

O MASTER inicia uma rotação e acompanha a confirmação, mas o fluxo normal não exibe o segredo. A implantação pode receber um token de bootstrap de uso único e curta duração, trocado por identidade de máquina. Recuperação extraordinária deve exigir permissão específica, motivo, auditoria e prazo. O gestor não recebe token de bootstrap, chave de API, certificado privado ou acesso ao Studio/cofre por gerenciar regras.

Separar os escopos de leitura de saúde, cadastro, permissões, abertura e configuração técnica sempre que o fabricante permitir. Se a API só oferecer conta administrativa ampla, encapsulá-la em adaptador de operações permitidas, reforçar rede e registrar o risco; não apresentá-la como conta de privilégio mínimo nativo.

Rede de leitores em segmento dedicado, sem encaminhamento público de administração. Acesso remoto de manutenção é temporário e aprovado. Definir DNS, NTP e destinos de saída; testar falhas de certificado e relógio. Não desabilitar validação TLS como solução definitiva. Evitar senhas e sessões em logs, inclusive parâmetros de URL de APIs que as utilizam.

Eventos externos são limitados por tamanho, frequência, profundidade e tipos. Validar assinatura/certificado quando suportado, origem mapeada e replay. O tenant é derivado da identidade técnica, não de tenantId livre no payload. Persistir inbox antes de confirmar recebimento; mensagens inválidas entram em quarentena com detalhe minimizado.

Uma release inclui hash de adaptador, dependências, protocolo, migração e política de capacidade. Mudança crítica exige ensaio e atualização do perfil. O desligamento de tenant suspende administração e novas concessões conforme o contrato, mas não deve trancar moradores nem eliminar saída física; a continuidade de acesso essencial e devolução operacional fazem parte do encerramento.

# 19 Privacidade biometria e responsabilidade local

Biometria ligada a pessoa natural é dado pessoal sensível. A escolha de base legal deve observar as hipóteses aplicáveis a esse tipo de dado; legítimo interesse de dados comuns não é fundamento automático para tratamento biométrico. A ANPD registra expressamente que essa hipótese não se aplica a dados sensíveis. [LG01, LG03]

Definir no contrato os papéis reais de controlador, operador e suboperadores. Como desenho inicial, o condomínio define a finalidade e as regras locais; o CondPulse presta o tratamento técnico contratado. Essa alocação precisa refletir a operação efetiva e não pode ser resolvida apenas renomeando um papel na interface. Designar contato de privacidade, avaliação de impacto e responsável por aprovar o uso em cada condomínio.

Para crianças e adolescentes, considerar o melhor interesse e a avaliação específica. O enunciado da ANPD admite as hipóteses dos artigos 7 e 11 conforme o caso, com prevalência do melhor interesse; isso não autoriza presumir uma base única para toda biometria de menores. Manter o gate de revisão antes da ativação. [LG02]

O dossiê registra finalidade, fundamento aprovado, avisos, direitos, alternativa de acesso, destinatários, localização e retenção. A integração não utiliza imagens para treinar modelos nem cria pesquisa biométrica entre condomínios. Histórico de entrada não vira ferramenta indiscriminada de monitoramento de outros adultos da unidade.

| Classe de dado | Diretriz de produto proposta | Controle operacional |
|---|---|---|
| Foto transitória | Apagar após aplicação ou prazo aprovado; referência inicial até 24 h | Falha prolongada pode exigir nova captura |
| Template no terminal | Apenas enquanto a finalidade autorizar | Exclusão por destino e prova de retirada |
| Evidência etária | Guardar mínimo suficiente | Data protegida ou atestação conforme finalidade |
| Evento de acesso | Prazo aprovado por condomínio; base inicial de 90 dias para discussão | Projeções por papel e exclusão programada |
| Payload bruto | Evitar; prazo curto quando necessário ao diagnóstico | Sem foto por padrão e acesso técnico controlado |
| Auditoria | Prazo compatível com finalidade documentada | Integridade e acesso restrito |
| Backup | Ciclo de retenção explícito | Registro de expurgo e revogações reaplicadas no restore |

Os prazos da tabela são parâmetros propostos, não exigências legais universais. A ativação exige preenchimento e aprovação no dossiê. Guardar metadados de exclusão suficientes para auditoria sem manter a própria biometria. Exportações exigem finalidade, minimização e controle de acesso; um arquivo de diagnóstico não deve carregar fotos, senhas ou lista completa de moradores.

# 20 Catálogo técnico e critérios de seleção

O catálogo desta versão reúne 27 registros de modelos/famílias, incluindo terminais faciais, catracas integradas e controladoras complementares. O objetivo é organizar a homologação dos cenários condominiais, contemplando parque existente e opções de expansão. O JSON permite importar os candidatos no sistema após revisão; nenhum registro vem com firmware homologado ou aprovação de produção.

| Critério de seleção | O que verificar | Consequência prática |
|---|---|---|
| População por ponto | Moradores visitantes prestadores e crescimento | Dimensionar por terminal e direitos necessários |
| Local de instalação | Abrigo iluminação chuva temperatura acessibilidade | Não inferir proteção de modelo semelhante |
| Interfaces físicas | Canal relé sensor leitor auxiliar direção | Aprovar a topologia real |
| Integração | API SDK licença documentação regional | Evitar dependência não suportada |
| Decisão de acesso | Local ou remota antes do relé | Compatibilidade com as regras desejadas |
| Revogação e expiração | Leitura de estado e vigência offline | Limitar concessões residuais |
| Evidência | Contato de porta sensor de passagem histórico | Definir o que pode ser afirmado ao usuário |
| Operação | Suporte atualização reposição e custo | Escolher combinação sustentável |

P0 significa prioridade inicial de investigação/ensaio, não equipamento já aprovado nem compra obrigatória. P1 amplia cobertura após o primeiro adaptador. P2 atende casos especiais ou parque legado. A ordem real deve começar pelos modelos instalados nos condomínios candidatos, sem trocar equipamentos apenas para atender uma preferência documental.

Catálogos e fichas podem mudar. Registrar a revisão da fonte, a etiqueta e a resposta do equipamento. Se duas fontes oficiais divergem, manter o conflito e obter esclarecimento; não escolher silenciosamente o número maior. Grau IP é característica declarada, e não substitui projeto, abrigo, vedação, manutenção ou ensaio do local.

# 21 Control iD

## 21.1 Modelos e famílias selecionados

| Modelo ou família | Capacidade e função publicadas | Interfaces e ambiente | Prioridade e condição |
|---|---|---|---|
| iDFace Lite e Pro | Lite 3 mil faces; página atual Pro 30 mil, mas texto introdutório ainda cita 10 mil | IP65; Ethernet; Wiegand; módulo externo com relé | P0 conforme parque; resolver versão e licença. [CI01] |
| iDFace Max | 10 mil 50 mil ou 100 mil faces conforme licença | IP65; Ethernet; OSDP como periférico; relé interno e módulo externo conforme configuração | P0; testar licença topologia e capacidade observada. [CI02] |
| iDBlock Next | Catraca com até dois iDFace; página indica 3 mil Lite e 10 mil Pro | Ambiente interno; TCP/IP; confirmação de giro documentada | P1; não herdar capacidade de iDFace avulso sem confirmação. [CI03] |
| iDBlock Balcão Facial | Catraca com até dois iDFace Max; até 100 mil faces conforme modelo | Operação standalone/online; identificação por sentido depende da montagem | P1; ensaiar leitor sentido e evidência de passagem. [CI04] |
| iDBox V2 | Controladora de quatro portas; sem reconhecimento facial próprio | Quatro relés; RS-485/OSDP; Secure Channel declarado; Wiegand compartilhado | P1 para múltiplas portas; leitor e controladora exigem combinação própria. [CI05] |

O fabricante diferencia ramos de firmware pelo prefixo do número de série de iDFace e iDFace Max. Atualização deve seguir esse ramo e o procedimento oficial; não há firmware universal para todos os aparelhos. A divergência de capacidade no iDFace fica registrada como pendência de perfil, e não como ampliação automaticamente disponível no parque. [CI01, CI02]

## 21.2 Caminho de integração

A documentação pública apresenta API REST por TCP/IP e modos Standalone, Pro e Enterprise. O caminho recomendado para investigar primeiro é reconhecimento no terminal e direitos locais representáveis; onde houver requisito dinâmico, testar Pro com decisão no gateway local. Enterprise delega identificação ao servidor e amplia a responsabilidade biométrica; não é necessário para o desenho inicial do CondPulse. [CI06, CI07]

| Necessidade | Referência oficial de operação | Cuidado de implementação |
|---|---|---|
| Pessoas grupos e horários | Objetos users groups access_rules e vínculos; create_objects | Reconciliação idempotente e grupos sem ampliação indevida. [CI10] |
| Cadastro facial | user_set_image.fcgi e user_set_image_list.fcgi | Validação por resultado individual; foto menor que 2 MB no envio unitário. [CI08] |
| Retirada facial | user_destroy_image.fcgi | Exclusão facial não deve ser confundida com simples limpeza da imagem original. [CI08] |
| Política online | new_user_identified.fcgi no servidor | Contrato de identificação Pro e resposta pré-relé a ensaiar. [CI13] |
| Eventos assíncronos | Monitor e notificações dao door catra_event conforme produto | Distinguir evento de identificação e evidência de passagem. [CI09, CI12] |
| Abertura | execute_actions.fcgi | Ação depende da família: door sec_box ou catra. [CI11, CI12] |

No cadastro facial, o equipamento pode ser configurado para não reter a foto original após gerar o template; isso é diferente de excluir o registro facial. O adaptador deve homologar ambas as operações e sua semântica. Evitar chamadas de remoção global e impedir fotos/sessões em logs. [CI08]

Ensaios específicos: contingência após falha de comunicação; acesso sem grupo; atualização de foto; expiração local; ação correta para SecBox/catraca; notificação de giro; retorno após reboot; licença e limites reais. Não interpretar o monitor assíncrono como a resposta de autorização do modo Pro.

# 22 Hikvision

## 22.1 Modelos selecionados

| Modelo exato | Faces e ambiente declarados | Integração e ligação documentadas | Condição de investigação |
|---|---|---|---|
| DS-K1T341AMF | 3 mil; IP65 | ISAPI ISUP 5.0 SDK; uma fechadura; Wiegand | P0 se instalado; validar firmware regional. [HK01] |
| DS-K1T342MFWX | 1.500; IP65 | ISAPI ISUP 5.0 Push SDK; RS-485 Wiegand; uma fechadura | P0; não substituir por E1 por analogia. [HK02] |
| DS-K1T343MWX | 1.500; uso interno | ISAPI ISUP Push SDK e SIP; uma fechadura | P1 para áreas internas. [HK03] |
| DS-K1T344MBWX-QRE1 | 3 mil; IP65 | QR PoE; Wiegand somente entrada na ficha | P1 condicionado à API; variante /BRASIL listada. [HK04] |
| DS-K1T671M-L | PT-BR 10 mil; global antiga 6 mil; IP65 | PT-BR ISAPI ISUP SDK SIP; uma fechadura | P0 se instalado; conflito exige identificação e confirmação. [HK05, HK06] |
| DS-K1T673DWX | 10 mil; IP65 | ISAPI ISUP Push SDK SIP; OSDP V2.1.7 em ficha de abril de 2026 | P0 para portaria de maior volume. [HK07] |
| DS-K1T673DWX-E1 | 10 mil; IP65 | ISAPI ISUP OSDP PoE; uma fechadura | P0 conforme variante instalada; não equiparar ao modelo sem E1. [HK08] |
| DS-K1T680DW | 100 mil; proteção IP não comprovada na ficha consultada | ISAPI ISUP RS-485 Wiegand; uma fechadura | P2 quando volume justificar. [HK09] |

## 22.2 Integração e restrições

ISAPI embarcado e HikCentral OpenAPI são integrações distintas. O portal TPP disponibiliza guias por série com condições de licença de material. O guia HikCentral descreve recursos de pessoa, face, privilégios, porta e resultado da aplicação, mas sua existência não garante licença instalada no condomínio. [HK10, HK11, HK13]

A referência pública oficial do protocolo de acesso contém operações UserInfo/Search, UserInfo/SetUp, FDLib/FaceDataRecord, FDSearch, UserInfoDetail/Delete e DeleteProcess. Também descreve doorRight, RightPlan e remoteCheck. Ela orienta a análise do adaptador, porém é documentação do portal chinês e não comprova disponibilidade em todo SKU brasileiro. Obter o guia aplicável e consultar capacidades do equipamento escolhido. [HK12]

Não presumir suporte a ISAPI no DS-K1T344MBWX-QRE1 apenas por pertencer à linha MinMoe. Para o DS-K1T671M-L, manter capacidade indefinida no perfil até resolver a diferença documental. Para OSDP nos modelos 673, exigir comprovação de modo, interoperabilidade e Secure Channel efetivamente habilitado quando esse controle for requerido.

O módulo DS-K2M061 é uma opção documentada de atuação protegida por RS-485 em combinações MinMoe específicas. Isso não transforma qualquer ligação RS-485 em OSDP nem aprova automaticamente a instalação. Conferir saída, sensor, botoeira e circuito de emergência na combinação real. [HK14]

Ensaios específicos: combinação de planos sem união permissiva inesperada; exclusão assíncrona com consulta de progresso; negativa remoteCheck antes do relé; resposta tardia; recuperação de eventos; Wiegand na direção suportada; validade e retomada offline. Evento recebido após abertura permanece apenas evidência histórica.

# 23 Dahua Technology

## 23.1 Modelos selecionados

| Modelo exato | Faces e ambiente declarados | Integração e função | Condição de investigação |
|---|---|---|---|
| DHI-ASI6214D-W | 6 mil; interior | CGI SDK/API OSDP SIP; uma fechadura | P0; RS-485/Wiegand compartilham porta. [DH01] |
| ASI6214J-MFW-V1 | 20 mil; IP65 | CGI SDK/API OSDP SIP; uma fechadura | P0; sufixo V1 obrigatório no perfil. [DH02] |
| ASI8214S-W-V1 | 100 mil; IP65 | SDK/API OSDP SIP; uma fechadura e duas saídas de alarme | P0 de maior capacidade; ficha vinculada tem título sem V1. [DH03] |
| ASI3214A-W-E1 | 3 mil; interior | CGI SDK/API OSDP; uma fechadura | P1; modalidade QR precisa confirmação. [DH04] |
| ASI3213G-MW | 1.500; interior | CGI SDK OSDP RS-485; uma fechadura | P1 de parque existente; não inferir Wiegand. [DH05] |
| ASI7213X-T1 | 100 mil; interior | CGI SDK OSDP; uma fechadura | P2 legado; marcado descontinuado no catálogo global. [DH06] |
| ASC3202B | Controladora sem face própria | Uma porta bidirecional ou duas unidirecionais; CGI SDK/API | P0 para relé protegido; mil usuários master ou 10 mil sub. [DH07] |

## 23.2 Integração e restrições

Integração direta exige pacote CGI/SDK aplicável ao SKU e firmware, com autenticação, métodos, limites e licença confirmados. Não reutilizar chamadas Intelbras apenas por semelhança de linha. A existência de NetSDK para uma plataforma não comprova que todas as funções de acesso estejam disponíveis no sistema operacional escolhido. [DH09]

DSS é um caminho alternativo quando já existe ou quando uma função necessária justifica sua adoção. O programa de integração descreve pessoas, portas, eventos e visitantes, com processo de acesso à API e licença de desenvolvimento. Confirmar condições de produção, NDA aplicável, cobrança e responsabilidade de suporte antes de torná-lo dependência do CondPulse. [DH10, DH10B]

O manual Web 5.0 diferencia registro automático por SDK, registro CGI e envio de pessoas/registros. A palavra push ou o campo Remote Verification em uma ficha não demonstram, sozinhos, que uma negativa externa impede o relé. Esse comportamento precisa ser provado na combinação exata e em timeout. [DH11]

Na controladora ASC3202B, limites dependem do modo principal ou secundário. Não dimensionar um projeto master com capacidade do modo sub. Na linha ASI, duas saídas de alarme não equivalem a duas portas de autorização. Modelos térmicos legados não justificam coletar temperatura no escopo facial do CondPulse.

Ensaios específicos: canal compartilhado RS-485/Wiegand; capacidade por modo; semântica da verificação remota; estados após reconnect; contratos de evento e recuperação; exclusão de todos os métodos; combinação leitor-controladora e comportamento sem conexão. ASI7213X-T1 exige avaliação de suporte e reposição antes de reaproveitamento.

# 24 Intelbras

## 24.1 Modelos selecionados

| Modelo exato | Faces e ambiente declarados | Interfaces e função | Condição de investigação |
|---|---|---|---|
| SS 3532 MF W | 10 mil na ficha nova; IP55 | API/CGI OSDP Wiegand RS-485; PoE; um relé | P0; capacidade ligada ao firmware indicado. [IB01, IB02] |
| SS 3542 MF W | Até 10 mil faces; digitais adicionais; IP55 no comparativo | Wiegand RS-485 leitor OSDP; PoE conforme revisão | P1; aprovar variante com digital. [IB03, IB04, IB05] |
| SS 5532 MF W | 50 mil na ficha nova; IP55 | API/CGI OSDP Wiegand RS-485; um relé | P0; fontes divergem em ramo de firmware e SIP. [IB06, IB07] |
| SS 5542 MF W | 50 mil faces e 10 mil digitais; IP55 | API OSDP Wiegand RS-485; um relé | P1; confirmar revisão e expansão de capacidade. [IB08] |
| SS 5531 MF EX | 50 mil na ficha de julho de 2026; IP55 | API/CGI leitor OSDP; Ethernet; um relé | P1; EX não equivale ao modelo MF W. [IB09] |
| SS 3531 MF LITE | 6 mil; interior | API/CGI leitor OSDP; Ethernet; RS-485/Wiegand compartilhado | P1; sem presunção de Wi-Fi ou proteção externa. [IB10, IB11] |
| SS 7530 FACE | 100 mil na ficha de 2020; IP65 | CGI SDK/API OSDP; um controle de porta e relés de alarme | P2 parque existente; distinguir SS75xx V2 e MF FACE. [IB12, IB13] |

Não há confirmação de fim de vida para todo modelo antigo da lista. Registrar idade da documentação e consultar suporte. Equipamentos SS 3530, SS 3540, SS 5530, SS 5531/5541 MF W e outras variantes que apareçam no inventário entram como novos registros documentais; não herdam a homologação do sucessor.

## 24.2 Integração e restrições

O fabricante mantém programa de softwares parceiros. Há documentação histórica pública da API de acesso v1.3, de 2020, e procedimentos de solicitação de SDK/API. Essa referência descreve operações de pessoa, face, vigência, eventos e porta, mas não deve ser usada como contrato presumido para todo firmware de 2026. Obter o pacote atual por SKU e preservar a revisão usada nos testes. [IB16, IB17, IB18]

No SS 3532/3542 MF W, o manual de interface web descreve modo Online com resposta de autorização do servidor e decisão offline com usuários locais no timeout. Essa condição é impeditiva para uma política que só exista na nuvem e deixe usuários locais amplamente autorizados. Cadastro de face e restrição de ingresso devem ser tratados separadamente. [IB19]

O manual da família também descreve que ativar o módulo de segurança desativa conexões locais de fechadura, botoeira, sensor e incêndio no facial. Módulo de segurança e módulo de porta adicional têm funções diferentes. Configurar XR 2201 exige verificar a ligação efetiva; essa função não pertence à tela do gestor. [IB04]

InControl não é uma dependência obrigatória deste desenho. Seu manual descreve ambiente Windows/LAN e restrições de implantação, além de desaconselhar integração por alteração direta do banco. Se for utilizado como plataforma intermediária, validar API suportada, capacidade, licença e quem escreve direitos. Não planejar simplesmente colocar o software em um contêiner Linux do backend. [IB14]

Ensaios específicos: branch de firmware e capacidade; diferenças de SIP; timeout online; segunda porta; mudança de modo serial; revogação com credenciais alternativas; foto rejeitada; atualização que exige recriação; origem dos eventos; QR seguro versus normal. O rótulo anti-fake de uma ficha não substitui avaliação de ataques de apresentação em bancada.

# 25 Comparação de caminhos e primeira homologação

| Caminho | Vantagem documental observada | Dependências a resolver | Escolha proposta |
|---|---|---|---|
| Control iD direto | API pública com objetos modos e exemplos | Capacidade licença firmware e contingência | Bom candidato para primeira prova técnica |
| Hikvision ISAPI | Fichas e guias por família; gestão de acesso | Guia aplicável região e recursos pré-relé | Priorizar quando presente no parque |
| Dahua CGI ou SDK | Produtos declaram API e interfaces | Pacote por firmware licença e contratos | Priorizar conforme inventário e suporte |
| Intelbras API Bio-T | Catálogo brasileiro e manuais detalhados | Revisão atual da API e diferenças de firmware | Priorizar modelos instalados e suporte local |
| Plataforma intermediária | Pode concentrar parque heterogêneo | Licença custos confirmação por destino e dependência | Avaliar quando já existe ou entrega recurso necessário |

Esta comparação é uma recomendação de engenharia baseada na pesquisa, não uma nota de segurança ou desempenho medido. Como ponto inicial documental, Control iD oferece uma superfície pública clara; como decisão de implantação, vence a combinação real do condomínio que atender aos gates. [CI06, HK10, DH09, IB16]

Selecionar um condomínio piloto, um caminho de integração e poucos pontos representativos: ingresso, área restrita e saída com evidência apropriada. Implementar o motor de regras e contrato comuns para as quatro marcas desde o início; homologar adaptadores em sequência. Isso permite ampliar cobertura sem prometer compatibilidade universal antes dos ensaios.

Para cada proposta comercial, exigir custo de equipamento, controlador, módulo de segurança, gateway, instalação, licença de API, plataforma intermediária, suporte, atualização e reposição. Não existe orçamento validado nesta pesquisa. Capacidade anunciada e conectividade de catálogo não comprovam custo total nem qualidade em campo.

# 26 Dossiê obrigatório de cada condomínio

O arquivo implantacao/Dossie_Condominio_Template.md acompanha o pacote e também possui versão editável e PDF. Ele deve ser duplicado e preenchido para cada tenant. Não é necessário inventariar dados pessoais no dossiê técnico; utilizar IDs e evidências minimizadas.

| Bloco do dossiê | Conteúdo obrigatório | Responsável por validar |
|---|---|---|
| Identificação e governança | Tenant fuso responsáveis contatos regulamento | Gestor e MASTER |
| Inventário | SKU série hardware firmware licença capacidade | MASTER implantação |
| Planta e percursos | Todas as passagens rotas acessíveis e essenciais | Integrador e gestor |
| Mapa de atuação | Área ponto sentido leitor controlador canal sensores | MASTER implantação |
| Regras | Categoria idade horário reserva convite exceção | Gestor |
| Execução | Quem decide antes do relé e fallback por ponto | MASTER implantação |
| Rede e energia | Segmentos comunicação relógio nobreak e falhas | Integrador |
| Privacidade | Finalidade base aprovada alternativa retenção | Responsável local designado |
| Ensaios | Casos resultado evidência e pendências | Qualidade e integrador |
| Entrada em operação | Janela plano de retorno contatos e treinamento | MASTER e gestor |

O inventário e a matriz de áreas também são entregues em CSV com cabeçalho e uma linha EXEMPLO. Remover a linha sintética antes de cadastrar informações reais. As colunas de segredo aceitam somente referência controlada; nunca senha, token, chave privada ou URL com credencial.

Uma combinação aprovada no laboratório ainda precisa do aceite de instalação. Altura e orientação de câmera, contraluz, cabos, perdas de rede, portas auxiliares e fluxo de pessoas mudam o resultado. Registrar testes nos horários relevantes do prédio, incluindo atendimento de pessoas com diferentes alturas e necessidades de acessibilidade.

# 27 Plano de homologação e critérios de aprovação

## 27.1 Níveis de evidência

DOCUMENTAL confirma fonte, variante e contrato disponível. CONTRATO valida regras e integração em ambiente controlado, com simuladores ou servidor de teste quando apropriado. BANCADA usa a combinação real de equipamento e firmware. CAMPO verifica a instalação e operação do condomínio. Aprovar um nível não substitui os demais exigidos para a capacidade.

O pacote inclui 100 casos HAC-001 a HAC-100, todos NAO_EXECUTADO. A matriz registra pré-condição, procedimento, resultado esperado, evidência, responsável e campos para resultado observado. Casos de segurança P0 são impeditivos para o escopo afetado. Não marcar NÃO APLICÁVEL sem justificar a ausência da função e impedir que ela seja comercializada ou configurada.

| Gate | Evidência mínima | Motivo de bloqueio |
|---|---|---|
| G0 Documentação | SKU contrato licença revisão e inventário | API inacessível ou combinação indefinida |
| G1 Domínio | Isolamento e decisões positivas/negativas | Tenant cruzado ou precedência incorreta |
| G2 Integração | Cadastro consulta retirada e eventos | Sucesso sem confirmação ou remoção inconclusiva |
| G3 Bancada | Restrição antes do relé em falha e normalidade | Abertura indevida ou fallback permissivo |
| G4 Instalação | Cada canal sentido sensor e saída | Mapeamento errado ou risco operacional |
| G5 Piloto | Operação monitorada e treinamento | Pendência crítica ou contingência não praticável |
| G6 Produção | Aceites técnicos e de regras | Ausência de evidência ou responsável |

## 27.2 Ensaios mínimos por classe

Regras devem incluir morador/visitante, menor/maioridade, idade desconhecida, convite revogado, reserva cancelada, feriado, janela que atravessa meia-noite, múltiplos vínculos e credencial alternativa. Cada negativa precisa demonstrar que a porta não foi liberada indevidamente, e não apenas que o painel apresentou erro.

Sincronização inclui timeout após envio, atualização concorrente, um destino offline, callback antigo, duplicação, reinício do worker, esgotamento de capacidade e restore. Biometria inclui erro de foto, identidade incorreta, qualidade e ataques autorizados de apresentação. Operação inclui falta de internet, gateway, leitor, controlador e energia em testes separados.

Segurança da plataforma inclui autorização direta por endpoint, exportação, contexto do tenant, rotação de segredo, revogação de suporte, isolamento de logs e inadmissibilidade de comandos arbitrários. Testes com dois condomínios devem reutilizar deliberadamente IDs nativos iguais para detectar dependência incorreta de chave global.

## 27.3 Metas iniciais para medir

As metas abaixo são critérios iniciais propostos, a ajustar e aprovar na bancada. Não são desempenho garantido dos fabricantes. Registrar população carregada, topologia, hardware, número de repetições, p50/p95/p99 e condições ambientais.

| Métrica | Meta inicial proposta | Forma de avaliar |
|---|---|---|
| Decisão do motor no gateway | p95 até 200 ms | Sem incluir captura facial; medir com carga |
| Identificação até comando ao relé | p95 até 1,5 s no cenário piloto | Medir todas as etapas e falhas |
| Publicação individual online | p95 até 60 s | Aplicação por destino, não só ACK |
| Revogação online | p95 até 30 s | Último destino necessário confirmado |
| Alerta de destino indisponível | Até 60 s após limiar de heartbeat | Sem marcar revogação concluída |
| Abertura expirada ou repetida | Zero acionamentos nos casos negativos | Ensaiar desconexão e resposta perdida |
| Abertura de área indevida | Zero nos casos críticos ensaiados | Não inferir taxa universal de falha zero |

Definir metas de reconhecimento legítimo e rejeição indevida por protocolo acordado com amostra representativa; não adotar percentuais publicitários como medição do projeto. Um ensaio pequeno com zero falhas não certifica a taxa de falsa aceitação na população. Conservar condições e resultados, sem alegações estatísticas superiores à evidência.

# 28 Comissionamento migração e entrada em operação

Primeiro registrar inventário e confirmar o sistema que atualmente controla as portas. Fazer cópia de configuração autorizada, identificar direitos vigentes e planejar retorno. Não operar dois sistemas escrevendo a mesma lista ao mesmo tempo. Uma fase de observação pode comparar eventos e decisões simuladas sem atuar nas portas.

Preparar o tenant de homologação com dados sintéticos. A homologação de software usa ambiente próprio; o piloto físico usa dispositivos e rede de teste ou janela de comissionamento acordada. Não copiar o banco integral de moradores de um condomínio para formar laboratório. Testar usando pessoas participantes e finalidade documentada.

Comissionar uma porta por vez. Conferir etiqueta, ponto, sentido, canal, sensor e saída; realizar tentativa permitida e negada; verificar alternativa operacional. Ativar o modo aprovado somente após esses passos. A passagem para a próxima porta depende do fechamento das evidências críticas da anterior.

Durante o corte, congelar mudanças simultâneas, reconciliar direitos, confirmar dispositivos e deixar portaria/integrador disponíveis. Um rollback operacional pode exigir configuração no próprio hardware e coordenação presencial. Retornar a versão da API não desfaz automaticamente um cadastro ou relé já alterado.

Os critérios de retorno incluem abertura indevida, perda de restrição, revogação inconclusiva com risco, falha de saída, eventos insuficientes para operar ou indisponibilidade fora do limite aceito. Suspender a expansão e aplicar o procedimento local comprovado. Registrar causa e estado do parque antes de tentar novamente.

Após o piloto, promover a mesma versão imutável de software/adaptador para os próximos tenants, preservando regras locais. Cada novo condomínio executa seu comissionamento e aceite. Firmware, rede e topologia diferentes podem exigir novo perfil, mesmo quando o fabricante é o mesmo.

# 29 Observabilidade suporte e continuidade

O MASTER visualiza saúde por condomínio, conector, perfil e dispositivo; o gestor visualiza o impacto operacional por área. Exibir última comunicação, idade da regra aplicada, pendências de cadastro/revogação, desvio de relógio, ocupação de capacidade e divergências. A ausência de eventos não prova que uma porta está saudável.

| Incidente | Ação imediata | Condição para encerrar |
|---|---|---|
| Face não reconhecida | Oferecer alternativa e conferir cadastro/qualidade | Identidade e destinos verificados |
| Pessoa reconhecida sem acesso | Ler motivo e política aplicados | Regra correta ou revisão aprovada |
| Revogação pendente offline | Acionar contingência local proporcional | Todos os destinos confirmados ou isolamento físico documentado |
| Porta errada acionada | Suspender atuação do ponto e isolar localmente | Binding corrigido e reensaio completo |
| Porta aberta sem comando | Conferir sensor circuito e alteração local | Causa identificada e segurança restabelecida |
| Divergência de firmware | Suspender novas capacidades do perfil | Revisão e testes críticos aprovados |
| Evento duplicado ou atrasado | Preservar origem deduplicar reconciliar | Histórico consistente sem reabertura |
| Disco ou fila saturada | Priorizar revogações e reduzir ingestão não essencial | Capacidade recuperada sem perda silenciosa |
| Credencial técnica comprometida | Revogar identidade e rotacionar | Canal confiável e inventário reconciliado |

Logs operacionais usam IDs opacos e motivos mínimos. Métricas agregadas não precisam carregar nome, foto ou data de nascimento. Alertas devem indicar efeito concreto, como Academia com regra anterior há 12 minutos, e responsável pela ação. Não emitir notificação Entregue/Entrou apenas por HTTP 200.

Definir RTO e RPO por componente no dossiê. A disponibilidade física pode continuar com ACL local enquanto o histórico central atrasa, mas novas revogações terão limite de propagação. Backup inclui banco, configuração autorizada, catálogo de perfis, evidências de aceite e registros de revogação; segredos seguem mecanismo próprio do cofre. Testar restore e reconstrução em equipamento reserva.

Mudanças no firmware, SDK, adaptador, sentido, módulo, controlador, modo de decisão, timeout ou circuito exigem análise de impacto. O certificado pode ser suspenso para novas instalações até revalidação. Uma alteração cosmética de nome da área não exige a mesma bateria de ensaios, desde que preserve os identificadores e vínculos.

# 30 Telas e jornadas para implementar

## 30.1 Console MASTER

O console possui catálogo por marca/SKU, matriz de capacidade, evidências, versões aprovadas e instalações afetadas. A tela de homologação permite registrar resultado, anexar referência de evidência, aprovar com restrições, suspender e exigir reensaio. A promoção não deve existir como simples checkbox sem responsável e escopo.

A implantação orientada segue identificação do condomínio, inventário, rede, leitura de capacidades, mapa físico, ensaio de canais, validação offline, evidências e aceite. Operações que podem acionar a porta aparecem com nome real do local e exigem contexto de teste autorizado. A tela nunca revela senha ou permite colar uma requisição arbitrária de API.

## 30.2 Painel do gestor

O gestor cria a área lógica e escolhe público permitido, idade, horário, reserva, visitantes e necessidade de atendimento. O assistente informa capacidades disponíveis naquele ponto e impede seleção incompatível. O preview mostra quem ganha/perde acesso e por quê, com fatos minimizados.

A ação Salvar cria rascunho. Validar verifica regra e hardware. Publicar mostra aprovação local e destinos. A interface informa Publicação parcial, Entrada A confirmada, Entrada B pendente, em vez de dar sucesso global. Pessoas possuem lista das áreas autorizadas e vigências; credenciais técnicas não aparecem.

## 30.3 Portaria e morador

Portaria visualiza chegada, anfitrião, destino autorizado, condições de atendimento e ação dentro de seu turno/escopo. Não oferece menu de firmware, IP ou chave. Abertura exige ponto explícito e motivo; o histórico distingue pedido, comando e sinal da porta.

Morador visualiza seus direitos, solicitações, convites e situação de sincronização permitida. A informação Facial pronto na entrada social não implica pronto na academia. Um visitante recebe orientação e vigência de seu convite, sem dados de outros moradores. Mensagens de erro devem permitir atendimento humano sem expor informação sensível na tela do terminal.

# 31 Plano de implementação e entrega ao desenvolvimento

O backlog do pacote contém trabalho incremental com dependências, critérios de aceite e gates. A primeira fase é verificar o repositório atual do CondPulse, especialmente contratos e migrations posteriores ao ZIP. Não iniciar recriando os módulos de identidade e reservas que já estejam implementados.

| Fase | Resultado concreto | Gate de saída |
|---|---|---|
| A Inventário do sistema | Mapa do que existe e diferenças desta extensão | Código schema e contratos identificados |
| B Domínio e permissões | Áreas sujeitos regras simulação RBAC | Testes negativos e isolamento aprovados |
| C Persistência e publicação | Versões grants outbox reconciliação | Falhas concorrentes e revogações cobertas |
| D Primeiro adaptador e gateway | Uma combinação completa em bancada | Cadastro restrição evento revogação offline |
| E Experiência operacional | Console MASTER e gestor com estados reais | Jornadas e acesso direto a endpoints verificados |
| F Condomínio piloto | Dossiê preenchido comissionamento e treinamento | Aceites técnico e de regras |
| G Expansão do catálogo | Novas combinações e condomínios | Evidência por combinação e instalação |

Não atribuir prazo fechado à homologação física sem equipamento, firmware, API, instalador e janela de teste disponíveis. Desenvolvimento do núcleo e preparação de bancada podem ocorrer em paralelo. A indisponibilidade de uma API proprietária deve bloquear aquele adaptador, não induzir endpoints inventados ou compatibilidade por similaridade.

O handoff para Claude Code acompanha o ZIP. Ele orienta preservar a base atual, trabalhar em branch, usar dados sintéticos, criar migrations novas, implementar contratos e rodar os testes aplicáveis. Mudança em produção e acionamento físico não são consequências automáticas de anexar esta documentação; fazem parte do procedimento de implantação aprovado.

# 32 Critérios de aceite do produto

A integração estará pronta para um condomínio quando a combinação técnica tiver perfil aprovado, todas as passagens relevantes estiverem mapeadas, regras locais forem aprovadas pelo gestor, testes críticos estiverem evidenciados, contingência tiver sido praticada e o MASTER assinar a liberação técnica. Não basta mostrar uma face reconhecida ou abrir uma única porta.

O aceite técnico lista SKU, hardware, firmware, adaptador, topologia, canais, capacidades aprovadas, restrições, data e evidências. O aceite de regras registra versão do regulamento/política, públicos, idade, visitantes, reservas, exceções, retenção e responsável. Uma assinatura não substitui a outra.

Nenhum candidato pesquisado nesta entrega recebe aprovação física por inferência. A documentação está pronta para implementação e execução dos ensaios; a matriz começa pendente para evitar que o sistema confunda planejamento com teste realizado. O certificado emitido pelo MASTER é um aceite interno CondPulse para um escopo definido, não certificação de produto emitida pelo fabricante ou organismo externo.

# 33 Referências e rastreabilidade

As fontes a seguir são oficiais dos fabricantes, documentação oficial de banco e referências normativas. As descrições de arquitetura, prioridades e metas são propostas do CondPulse. A consulta foi realizada em 30 de outubro de 2026. Revisões e datas foram registradas quando disponíveis; data em nome de arquivo não equivale, por si só, a confirmação de firmware instalado.

As referências BASE01 apontam ao pacote fornecido pelo solicitante. O catálogo estruturado mantém source_ids por modelo. A comprovação em bancada e campo deverá acrescentar seus próprios IDs de evidência, sem substituir as fontes documentais.

## Fontes catalogadas

**BASE01** CondPulse Pacote Completo v1.0 fornecido pelo solicitante. Arquivo fornecido pelo solicitante. README, PRD, contrato de integração, V001/V002, OpenAPI e guia de marca; 27/10/2026. Consulta 30/10/2026.

**CI01** Control iD iDFace. [Abrir fonte oficial](https://www.controlid.com.br/controle-de-acesso/idface/). Página atual; capacidade conflitante no próprio texto; ramos de firmware por série. Consulta 30/10/2026.

**CI02** Control iD iDFace Max. [Abrir fonte oficial](https://www.controlid.com.br/controle-de-acesso/idface-max/). Página atual; licença por capacidade; interfaces e ramos de firmware. Consulta 30/10/2026.

**CI03** Control iD iDBlock Next. [Abrir fonte oficial](https://www.controlid.com.br/controle-de-acesso/idblock-next/). Página atual; catraca, capacidade e passagem. Consulta 30/10/2026.

**CI04** Control iD iDBlock Balcão Facial. [Abrir fonte oficial](https://www.controlid.com.br/controle-de-acesso/idblock-balcao-facial/). Página atual; catraca com iDFace Max. Consulta 30/10/2026.

**CI05** Control iD iDBox V2. [Abrir fonte oficial](https://www.controlid.com.br/controle-de-acesso/idbox-v2/). Página atual; quatro portas e OSDP Secure Channel declarado. Consulta 30/10/2026.

**CI06** Control iD Introdução à API. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/). Documentação pública; API e modos. Consulta 30/10/2026.

**CI07** Control iD Modos de operação. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/modos-de-operacao/introducao-aos-modos-de-operacao/). Standalone Pro Enterprise e contingência. Consulta 30/10/2026.

**CI08** Control iD Cadastro facial por fotos. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/reconhecimento-facial/cadastro-facial/). Cadastro leitura remoção e tratamento da imagem original. Consulta 30/10/2026.

**CI09** Control iD Introdução ao Monitor. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/monitor/introducao-ao-monitor/). Notificações assíncronas por tipo. Consulta 30/10/2026.

**CI10** Control iD Cadastrar usuários e suas regras. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/primeiros-passos/cadastrar-usuarios-e-suas-regras/). Objetos grupos regras e horários. Consulta 30/10/2026.

**CI11** Control iD Abertura remota de porta e catraca. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/acoes/abertura-remota-porta-e-catraca/). Ações específicas door sec_box e catra. Consulta 30/10/2026.

**CI12** Control iD Particularidades dos terminais. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/particularidade-dos-produtos/particulariade-terminais-control-id/). Diferenças por produto; tipo de ação e evento. Consulta 30/10/2026.

**CI13** Control iD Eventos de identificação online. [Abrir fonte oficial](https://www.controlid.com.br/docs/access-api-pt/modos-de-operacao/eventos-de-identificacao-online/). Contrato de identificação no modo Pro. Consulta 30/10/2026.

**LG01** Câmara dos Deputados LGPD texto atualizado. [Abrir fonte oficial](https://www2.camara.leg.br/legin/fed/lei/2018/lei-13709-14-agosto-2018-787077-normaatualizada-pl.html). Lei 13.709 de 2018; artigos 5 11 14 e controles relacionados. Consulta 30/10/2026.

**LG02** ANPD Enunciado sobre crianças e adolescentes. [Abrir fonte oficial](https://www.gov.br/anpd/pt-br/assuntos/noticias/anpd-divulga-enunciado-sobre-o-tratamento-de-dados-pessoais-de-criancas-e-adolescentes). 24/05/2023; melhor interesse e hipóteses legais. Consulta 30/10/2026.

**LG03** ANPD Guia de legítimo interesse. [Abrir fonte oficial](https://www.gov.br/anpd/pt-br/assuntos/noticias/anpd-lanca-guia-orientativo-sobre-legitimo-interesse). 02/02/2024; hipótese não aplicável a dados sensíveis. Consulta 30/10/2026.

**DB01** Supabase Row Level Security. [Abrir fonte oficial](https://supabase.com/docs/guides/database/postgres/row-level-security). Documentação atual; grants RLS papéis e user_metadata. Consulta 30/10/2026.

**DB02** PostgreSQL Row Security Policies. [Abrir fonte oficial](https://www.postgresql.org/docs/current/ddl-rowsecurity.html). Documentação current consultada; confirmar versão real do banco. Consulta 30/10/2026.

**HK01** DS-K1T341AMF, Datasheet V1.1, arquivo 08/03/2023.. [Abrir fonte oficial](https://www.hikvision.com/content/dam/hikvision/products/S000000001/S000000080/S000000103/S000000107/OFR000141/M000039878/Data_Sheet/DS-K1T341AMF-Face-Recognition-Terminal_Datasheet_V1.1_20230308.pdf). Conforme título e documento. Consulta 30/10/2026.

**HK02** DS-K1T342MFWX, Datasheet, arquivo 06/05/2026.. [Abrir fonte oficial](https://assets.hikvision.com/prd/normal/all/doc/m000054133/DS-K1T342MFWX_Datasheet_20260506.pdf). Conforme título e documento. Consulta 30/10/2026.

**HK03** DS-K1T343MWX, página oficial global, sem data editorial.. [Abrir fonte oficial](https://securitylab.hikvision.com/en/products/Access-Control-Products/terminals/face-recognition-terminal/ds-k1t343mwx/). Conforme título e documento. Consulta 30/10/2026.

**HK04** DS-K1T344MBWX-QRE1, Datasheet, arquivo 25/07/2025.. [Abrir fonte oficial](https://assets.hikvision.com/prd/normal/all/doc/m000126763/DS-K1T344MBWX-QRE1_Datasheet_20250725.pdf). Conforme título e documento. Consulta 30/10/2026.

**HK05** DS-K1T671M-L, ficha PT-BR, sem data editorial identificada.. [Abrir fonte oficial](https://www.hikvision.com/content/dam/hikvision/pt-br/DS-K1T671M-L-PTBR.PDF). Conforme título e documento. Consulta 30/10/2026.

**HK06** DS-K1T671M-L, ficha global antiga, sem data editorial identificada.. [Abrir fonte oficial](https://www.hikvision.com/content/dam/hikvision/products/S000000001/S000000080/S000000103/S000000105/OFR000135/M000029752/Data_Sheet/DS-K1T671M-L.pdf). Conforme título e documento. Consulta 30/10/2026.

**HK07** DS-K1T673DWX, Datasheet, arquivo 28/04/2026.. [Abrir fonte oficial](https://assets.hikvision.com/prd/normal/all/doc/m000049932/DS-K1T673DWX_Datasheet_20260428.pdf). Conforme título e documento. Consulta 30/10/2026.

**HK08** DS-K1T673DWX-E1, Datasheet, arquivo 23/12/2025.. [Abrir fonte oficial](https://assets.hikvision.com/prd/normal/all/doc/m000173870/DS-K1T673DWX-E1_Datasheet_20251223.pdf). Conforme título e documento. Consulta 30/10/2026.

**HK09** DS-K1T680DW, Datasheet, arquivo 27/12/2023.. [Abrir fonte oficial](https://assets.hikvision.com/prd/public/all/doc/m000039971/DS-K1T680DW_Datasheet_20231227.pdf). Conforme título e documento. Consulta 30/10/2026.

**HK10** TPP, ISAPI & OTAP Developer Guide, sem data editorial.. [Abrir fonte oficial](https://tpp.hikvision.com/download/). Conforme título e documento. Consulta 30/10/2026.

**HK11** Hikvision Open Capabilities, sem data editorial; conteúdo primário indexado, abertura direta expirou.. [Abrir fonte oficial](https://tpp.hikvision.com/tpp/OpenCapabilities). Conforme título e documento. Consulta 30/10/2026.

**HK12** Protocolo de acesso MinMoe, documentação oficial pública em chinês, sem data editorial identificada. [Abrir fonte oficial](https://open.hikvision.com/hardware/v2/08%E5%8D%8F%E8%AE%AE%E9%80%8F%E4%BC%A0/%E6%98%8E%E7%9C%B8%E9%97%A8%E7%A6%81.html). Consulta 30/10/2026.

**HK13** HikCentral Professional OpenAPI V3.1.1, documentação oficial, caminho de publicação 23/07/2026.. [Abrir fonte oficial](https://enpinfo.hikvision.com/hkwsen/unzip/20260723200832_95640_doc/index.html). Conforme título e documento. Consulta 30/10/2026.

**HK14** How to use Minmoe to connect to security module DS-K2M061 by RS485, data impressa 03/03/2025.. [Abrir fonte oficial](https://www.hikvision.com/content/dam/hikvision/en/support/how-to/how-to-document/video-intercom/How-to-use-Minmoe-to-connect-to-security-module-DS-K2M061-by-RS485.pdf). Conforme título e documento. Consulta 30/10/2026.

**DH01** Dahua Brasil — DHI-ASI6214D-W. [Abrir fonte oficial](https://www.dahuasecurity.com/br/products/Access-Control/AI/Standalone/Lite/DHI-ASI6214D-W). 2025-08-15; manual 2026-08-10. Consulta 30/10/2026.

**DH02** Dahua — ASI6214J-MFW-V1. [Abrir fonte oficial](https://www.dahuasecurity.com/products/access-control-%26-time-attendance/ai/standalone/pro/asi6214j-mfw-v1). 2025-08-08; manual 2026-10-15. Consulta 30/10/2026.

**DH03** Dahua Brasil — ASI8214S-W-V1. [Abrir fonte oficial](https://www.dahuasecurity.com/br/products/Access-Control/AI/Standalone/ASI8214S-W-V1). 2025-07-23. Consulta 30/10/2026.

**DH04** Dahua — ASI3214A-W-E1. [Abrir fonte oficial](https://www.dahuasecurity.com/products/access-control-%26-time-attendance/ai/standalone/lite/asi3214a-w-e1). 2026-08-07. Consulta 30/10/2026.

**DH05** Dahua — ASI3213G-MW. [Abrir fonte oficial](https://www.dahuasecurity.com/products/Access-ControlTime-Attendance/AI/Standalone/ASI3213G-MW). 2020-06-22. Consulta 30/10/2026.

**DH06** Dahua — ASI7213X-T1. [Abrir fonte oficial](https://www.dahuasecurity.com/products/Access-ControlTime-Attendance/AI/Standalone/ASI7213X-T1). 2021-03-11. Consulta 30/10/2026.

**DH07** Dahua Brasil — ASC3202B. [Abrir fonte oficial](https://www.dahuasecurity.com/br/products/Access-Control/Controllers/ASC3202B). ficha 20231108; manual 2025-12-18. Consulta 30/10/2026.

**DH08** Dahua — ASC2204C-H. [Abrir fonte oficial](https://www.dahuasecurity.com/id/products/Access-Control/Controllers/ASC2204C-H). 2022-11-06. Consulta 30/10/2026.

**DH09** Dahua Partner Alliances — SDK downloads. [Abrir fonte oficial](https://previous-depp.dahuasecurity.com/integration/guide/download/sdk). índice 2025-12-25. Consulta 30/10/2026.

**DH09B** Dahua Brasil — Controle de Acesso. [Abrir fonte oficial](https://www.dahuasecurity.com/br/products/All-Products/Access-Control). Sem data editorial identificada. Consulta 30/10/2026.

**DH10** Dahua — Integration With DSS. [Abrir fonte oficial](https://www.dahuasecurity.com/products/software/ecosystem/integration-with-dss). Sem data editorial identificada. Consulta 30/10/2026.

**DH10B** Dahua Security System Integration Solution. [Abrir fonte oficial](https://materialfile.dahuasecurity.com/uploads/file/20250326/20250326172706_14341.pdf). arquivo 2025-03-26. Consulta 30/10/2026.

**DH11** Face Recognition Access Controller Web 5.0 User Manual V1.5.1. [Abrir fonte oficial](https://materialfile.dahuasecurity.com/uploads/cpq/72637/user_manual/Face_Recognition_Access_Controller_Web_5.0_User_Manual_V1.5.1.pdf). vinculado 2026-10-15. Consulta 30/10/2026.

**IB01** SS 3532 MF W — página de produto. [Abrir fonte oficial](https://www.intelbras.com/pt-br/controlador-de-acesso-facial-ss-3532-mf-w). Página atual consultada em 30/10/2026. Consulta 30/10/2026.

**IB02** SS 3532 MF W — ficha técnica abril/2026. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2026-04/Datasheet%20SS%203532%20MF%20W.pdf). 2026-04, caminho do documento. Consulta 30/10/2026.

**IB03** SS 3542 MF W — página de produto. [Abrir fonte oficial](https://www.intelbras.com/pt-br/controlador-de-acesso-facial-com-biometria-digital-ss-3542-mf-w). Página atual consultada em 30/10/2026. Consulta 30/10/2026.

**IB04** Manual usuário SS 3532 MF W / SS 3542 MF W. [Abrir fonte oficial](https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/manual-usuario-pt-BR/manual_SS35x2_MF_W.html). Versão 6.26. Consulta 30/10/2026.

**IB05** Comparativo — Leitores Faciais e Telas Condominial/Corporativo. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2026-01/Comparativo%20-%20Leitores%20Faciais%20e%20Telas%20Condominial%20%20Corporativo.pdf). 2026-01, caminho do documento. Consulta 30/10/2026.

**IB06** SS 5532 MF W — ficha técnica fevereiro/2026. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2026-02/Datasheet%20SS%205532%20MF%20W.pdf). 2026-02, caminho do documento. Consulta 30/10/2026.

**IB07** SS 5532 MF W — ficha técnica novembro/2025. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2025-11/Datasheet%20SS%205532%20MF%20W.pdf). 2025-11, caminho do documento. Consulta 30/10/2026.

**IB08** SS 5542 MF W — ficha técnica janeiro/2026. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2026-01/Datasheet%20SS%205542%20MF%20W.pdf). 2026-01, caminho do documento. Consulta 30/10/2026.

**IB09** SS 5531 MF EX — ficha técnica julho/2026. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2026-07/Datasheet%20SS%205531%20MF%20EX.pdf). Ajuda/download: 23/07/2026. Consulta 30/10/2026.

**IB10** SS 3531 MF LITE — ficha técnica abril/2026. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2026-04/Datasheet%20SS%203531%20MF%20Lite.pdf). 2026-04, caminho do documento. Consulta 30/10/2026.

**IB11** SS 3531 MF LITE — ficha técnica setembro/2025. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2025-09/Datasheet%20SS%203531%20MF%20Lite_0.pdf). 2025-09, caminho do documento. Consulta 30/10/2026.

**IB12** SS 7530 FACE — ficha técnica 2020. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2020-06/Datasheet-SS-7530-FACE.01.20.pdf). 01.20; Ajuda/download 30/06/2020. Consulta 30/10/2026.

**IB13** SS 7530 FACE — Ajuda e downloads. [Abrir fonte oficial](https://www.intelbras.com/pt-br/ajuda-download/download/controlador-de-acesso-facial-ss-7530-face). Consulta 30/10/2026; tutorial SDK/API 09/09/2024. Consulta 30/10/2026.

**IB14** InControl Web — manual oficial. [Abrir fonte oficial](https://manual-incontrol.intelbras.com.br/pt-BR/manual_pt-BR.html). Manual online consultado 30/10/2026. Consulta 30/10/2026.

**IB15** Intelbras — catálogo reconhecimento facial. [Abrir fonte oficial](https://www.intelbras.com/pt-br/seguranca-eletronica/controles-de-acesso-corporativo/reconhecimento-facial). Consulta 30/10/2026. Consulta 30/10/2026.

**IB16** Intelbras — Softwares Parceiros. [Abrir fonte oficial](https://software-parceiro.intelbras.com.br/). Consulta 30/10/2026. Consulta 30/10/2026.

**IB17** Documento de Integração via API — Controle de Acesso. [Abrir fonte oficial](https://suporte.intelbras.com.br/images/d/dd/Documento_de_Integrao_via_API_V1.3.pdf). Versão 1.3 — 25/11/2020. Consulta 30/10/2026.

**IB18** Documentação API — Linha Bio-t. [Abrir fonte oficial](https://backend.intelbras.com/sites/default/files/2022-03/INTEGRA%C3%87%C3%83O%20-%20API.pdf). 14/03/2022. Consulta 30/10/2026.

**IB19** Interface Web Bio-T — SS3532/3542 MF W. [Abrir fonte oficial](https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/pt-BR/manual_SS3532_SS3542_MFW_pt-BR.html?lang=pt). Manual online consultado 30/10/2026. Consulta 30/10/2026.

**IB20** Manual usuário SS5532/SS5532 V2/SS5542/SS5532 T W. [Abrir fonte oficial](https://manuais.intelbras.com.br/manual-interface-web-linha-bio-t/manual-usuario-pt-BR/manual_SS55x2_MF_T-W.html?lang=pt). Versão 7.26. Consulta 30/10/2026.

**IB21** SS5530 MF FACE — Ajuda e Downloads. [Abrir fonte oficial](https://www.intelbras.com/pt-br/ajuda-download/download/controlador-de-acesso-facial-ss-5530-mf-face). Consulta 30/10/2026. Consulta 30/10/2026.

**IB22** SS3540 MF FACE EX — Ajuda e Downloads. [Abrir fonte oficial](https://www.intelbras.com/pt-br/ajuda-download/download/controlador-de-acesso-facial-ss-3540-mf-face-ex). Consulta 30/10/2026. Consulta 30/10/2026.


**DB03** PostgreSQL — Constraints. [Abrir fonte oficial](https://www.postgresql.org/docs/current/ddl-constraints.html). CHECK, UNIQUE e chaves estrangeiras compostas, detalhadas no anexo do modelo de dados. Consulta 30/10/2026.
