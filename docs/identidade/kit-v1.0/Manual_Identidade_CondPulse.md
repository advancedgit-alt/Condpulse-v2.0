# Manual de identidade da marca e do sistema CondPulse

Versão proposta 1.0 • 31 de outubro de 2026

## Como usar este manual

Este documento define uma direção de identidade para a marca CondPulse e para suas interfaces de gestão, portaria, morador e MASTER. Reúne o diagnóstico do frontend existente, as regras de aplicação da marca, o sistema visual proposto, a personalização por cliente e o plano de integração. As pranchas são propostas de design; o diagnóstico identifica separadamente o que foi comprovado no código.

O ponto de partida recomendado é preservar o azul-marinho, o verde-petróleo e a fonte Lato, consolidar a marca vetorial que já está no repositório e transformar os estilos existentes em um conjunto coerente de tokens e componentes. O resultado esperado é um produto reconhecível, eficiente para operação e capaz de receber a identidade de cada cliente sem duplicar o frontend.

O protótipo contém pessoas, unidades, valores e eventos fictícios. Simula navegação e algumas decisões de interface. Não possui autenticação real, não consulta dados do condomínio, não envia mensagens e não aciona portas. A implantação dessas interfaces exige integração e testes no sistema real.

### Evidência e proposta

| Identificação | Significado |
| --- | --- |
| Confirmado no código | Observado no commit fixado da auditoria. |
| Proposta CondPulse | Decisão de design ou arquitetura recomendada neste documento. |
| Referência externa | Padrão observado em documentação ou material público de outra empresa. |
| A validar | Depende de teste com pessoas, dispositivos, canal ou ambiente real. |

A consulta ao repositório foi feita em 31/10/2026, na branch padrão `ccr-5e725112-3x8hyg`, commit `7130b52769ad2acaac420fd2896b32b1ad7484ad`. Essa evidência não confirma o SHA efetivamente servido em produção. O relatório completo da auditoria acompanha o pacote.

## 1 Qual tecnologia visual usamos hoje

**A web CondPulse usa React, TypeScript e Vite, com CSS próprio. Não foi identificado uso de Tailwind ou Bootstrap no código auditado.** TypeScript é a linguagem de programação; React organiza os componentes; CSS define a apresentação; Vite executa o build. Portanto, a resposta para “qual linguagem visual usamos” é: uma interface autoral, estilizada com CSS próprio.

| Camada | Situação comprovada | Consequência para a evolução |
| --- | --- | --- |
| Aplicação web | React e TypeScript | Preservar componentes e rotas; evoluir gradualmente. |
| Build | Vite | O manual não exige troca de ferramenta de build. |
| Estilos | Quatro folhas CSS com seletores globais | Centralizar tokens e reduzir regras repetidas. Não são CSS Modules. |
| Fonte | Lato 400 e 700 importadas em main.tsx | Consolidar pesos reais; não depender de 550/650/750/800 sem arquivo correspondente. |
| Componentes | Primitivos próprios de marca, ícone, aviso, carregamento, erro e vazio | Evoluir os componentes existentes antes de introduzir uma biblioteca paralela. |
| Ícones | SVGs locais | Normalizar tamanho, traço e alinhamento. |
| Gráficos | Nenhuma biblioteca externa de gráficos encontrada | As visualizações do protótipo são propostas novas. |
| Mobile | React Native, Expo e StyleSheet | Compartilhar significado dos tokens; gerar saída adequada ao nativo. |

O manifesto, o lockfile, as quatro folhas CSS e os imports dos 47 arquivos TSX web foram examinados. Não foram encontrados Tailwind, Bootstrap, Material UI, Radix/shadcn ou biblioteca equivalente no recorte auditado. Há React Router e TanStack Query, que tratam navegação e dados, não a identidade visual. [R01–R04]

### Biblioteca visual a adotar

A recomendação inicial é continuar com CSS próprio, criar tokens semânticos e fortalecer os componentes compartilhados. Trocar tudo por Tailwind ou por um kit pronto não resolve, por si só, inconsistências de marca, permissões, estados ou contraste. Uma biblioteca pode ser avaliada futuramente para uma necessidade concreta, com prova de acessibilidade, manutenção, licença e impacto na base existente.

Se houver adoção futura de componentes externos, definir antes o que será substituído, quais tokens serão consumidos e como evitar dois padrões para a mesma ação. Não instalar vários kits para obter uma aparência moderna. A consistência vem das regras do produto e de sua aplicação.

## 2 Diagnóstico de identidade e prioridades

Há três expressões visuais distintas no projeto consultado. A marca web é desenhada por `Brand()` com três barras em CSS e o nome CondPulse. Os arquivos de especificação têm um C envolvendo três edifícios. O ícone apontado pelo app mobile é uma forma azul em A, diferente das duas anteriores. O guia original identifica o vetor como proposta; o novo kit preserva esse status. [R03, R05, R06]

| Prioridade | Achado | Decisão recomendada |
| --- | --- | --- |
| P0 | Web, especificação e app usam símbolos diferentes | Eleger o SVG C/edifícios como candidato único e aplicar o mesmo ativo nas superfícies após aprovação da identidade. |
| P0 | Não foi encontrado motor completo de white label | Implementar resolução de tema por escopo e revisão; não oferecer apenas troca superficial de logo. |
| P1 | Web e mobile têm verde-petróleo e fundos diferentes | Gerar os dois a partir do mesmo contrato de tokens semânticos. |
| P1 | Pesos tipográficos solicitados excedem os importados | Padronizar inicialmente 400 e 700; carregar qualquer peso adicional adotado. |
| P1 | Tokens JSON existem, mas consumo automático não foi constatado | Tornar um pacote a fonte efetiva dos estilos; remover cópias divergentes após migração. |
| P1 | Tema escuro completo não foi identificado | Criar paleta própria e verificar cada componente e estado. |
| P2 | Densidade e metadados variam entre páginas | Definir escala de texto, espaçamento e controle por contexto. |

O shell atual já tem sidebar, cabeçalho, conteúdo e rotas por perfil. Essas estruturas devem ser preservadas e refinadas. Não é necessário transformar o produto em uma página promocional para modernizá-lo. O trabalho visual deve melhorar leitura, navegação e execução das tarefas já existentes.

## 3 Identidade da marca

### Papel da CondPulse

Proposta de posicionamento: CondPulse organiza a rotina do condomínio e conecta gestão, portaria e moradores em um produto com contexto claro. A marca deve comunicar organização, proximidade e domínio da operação. Isso é direção de comunicação, não promessa de resultado mensurado.

A expressão visual combina estrutura firme com detalhes acolhedores. O azul-marinho organiza a hierarquia; o verde-petróleo identifica ações e pontos de interesse; as superfícies claras favorecem leitura; a Lato mantém continuidade com o produto existente. Evitar efeitos ornamentais que disputem atenção com pessoas, locais, valores e decisões.

### Nome e assinatura

Escrever **CondPulse**, com C e P maiúsculos, sem espaço no meio. Em texto corrido, usar o nome como palavra; em aplicação institucional, usar o arquivo vetorial fornecido. Não substituir o lettering do logotipo por texto digitado para imitar a marca.

Assinaturas propostas para testar em comunicação: “A rotina do seu condomínio, organizada.” e “Pessoas, reservas e comunicação em um só lugar.” A primeira é a assinatura de campanha dos templates. A segunda descreve o escopo funcional. Evitar declarações absolutas como “segurança garantida”, “zero falhas” ou “homologado com qualquer equipamento”.

### Tom de voz

Usar linguagem direta, verbos concretos e informação suficiente para agir. Mensagens do sistema devem explicar o estado e o próximo passo. A marca pode ser cordial sem infantilizar a operação. Em eventos de acesso, manutenção e cobrança, priorizar clareza e responsabilidade.

| Situação | Texto recomendado | Evitar |
| --- | --- | --- |
| Regra salva | Rascunho salvo. Publique para aplicar a nova regra. | Tudo certo! quando ainda não foi publicado. |
| Dispositivo indisponível | Sem comunicação desde 08h31. Abertura remota indisponível. | Sistema offline sem indicar o impacto. |
| Convite vencido | Convite expirado às 18h. Solicite uma nova autorização. | Acesso inválido sem explicação. |
| Porta | Comando aceito. Abertura ainda não confirmada. | Porta aberta sem evidência do sensor. |
| Cobrança | Vencimento em 10/11/2026. Consulte os dados antes de pagar. | Urgência genérica ou pressão sem contexto. |

## 4 Sistema de logotipos

O kit deriva dos SVGs existentes em `spec/design/marca`. Foram preservados paths, transformações, espessura do traço e terminações. O lettering já está convertido em caminhos; os arquivos da marca não dependem da instalação da Lato. As variantes alteram cores, metadados e composição de suporte, sem redesenhar o símbolo. [R05]

| Variante | Composição | Aplicação |
| --- | --- | --- |
| Positiva | Navy e teal | Fundo branco ou claro validado. Padrão institucional. |
| Negativa | Branco e teal claro #5DD6C7 | Fundo navy #102A43. |
| Monocromática branca | Todos os elementos brancos | Fundo escuro uniforme ou placa escura sobre imagem. |
| Monocromática navy | Todos os elementos navy | Reprodução simples sobre fundo claro. |
| Símbolo isolado | C e edifícios | Favicon, avatar institucional, menu recolhido e espaços pequenos. |
| App icon | Símbolo na placa original | Referência para aplicativo/PWA; adaptar às exigências de distribuição. |

O horizontal e o símbolo isolado têm fundo transparente. O app icon mantém a placa do original. O pacote contém 12 SVG, 24 PNG em 2×/4×, seis PNG para favicon/touch icon e um ICO. O manifesto identifica origem e hashes.

### Fundo claro, escuro e colorido

Sobre branco, usar a positiva. Sobre navy, usar a negativa ou a branca. Sobre teal escuro, preferir a monocromática branca. Sobre cor de cliente, fotografia ou textura, usar uma placa neutra com proteção suficiente quando a leitura da marca não for garantida. Não recolorir o logotipo CondPulse para a cor do cliente; o white label usa os ativos do próprio cliente nos locais previstos.

O teal claro #5DD6C7 tem contraste aproximado de 8,30:1 sobre navy e de apenas 1,76:1 sobre branco. Sua função é apoiar a marca reversa e o tema escuro, não servir de texto pequeno sobre uma superfície branca. Logotipos têm exceções normativas específicas; botões e rótulos que reutilizam suas cores continuam sujeitos às regras da interface. [A01, A02]

### Proteção e redução

Nesta versão proposta, `x` corresponde a 25 unidades do desenho original, equivalentes à espessura nominal do traço externo do C. Reservar ao menos 1x ao redor da área visual da marca, sem considerar o canvas do arquivo como garantia de margem. No horizontal, para largura W, x = W × 25/724. Em 160 px, a proteção mínima calculada é cerca de 5,5 px; arredondar para cima.

Essa é uma evolução explícita: o guia inicial sugeria metade da altura do símbolo. Antes de tornar o manual definitivo, adotar uma única regra na governança da marca. As aplicações de campanha podem usar proteção maior; o mínimo não é uma meta de preenchimento.

| Uso | Mínimo proposto a validar |
| --- | --- |
| Logo horizontal digital | 160 px de largura |
| Símbolo isolado digital | 32 px de altura |
| Logo horizontal impresso | 30 mm de largura |
| Símbolo impresso | 8 mm de altura |
| Favicon | Arquivo dedicado, com verificação em 16/32 px no contexto |

Não deformar, inclinar, adicionar sombra, alterar proporção símbolo/nome, mudar os intervalos do lettering, inserir o logo em uma área sem proteção ou usar a negativa clara sobre branco. Em um espaço menor que o suportado, usar símbolo e nome acessível no componente, sem encolher o horizontal até perder leitura.

## 5 Paleta institucional e cores de interface

| Papel | HEX | RGB | Uso |
| --- | --- | --- | --- |
| Navy institucional | #102A43 | 16, 42, 67 | Marca, títulos, texto principal e contextos escuros. |
| Teal institucional | #007F86 | 0, 127, 134 | Marca e ação primária com texto branco em fundo sólido. |
| Fundo claro | #F4F7FA | 244, 247, 250 | Plano de trabalho. |
| Superfície | #FFFFFF | 255, 255, 255 | Cards, tabelas, modais e formulários. |
| Texto secundário | #52667A | 82, 102, 122 | Metadados e explicações. |
| Separador | #DCE5EC | 220, 229, 236 | Divisões decorativas de regiões. |
| Link reforçado proposto | #00666D | 0, 102, 109 | Texto de links sobre superfícies claras. |
| Teal reverso proposto | #5DD6C7 | 93, 214, 199 | Marca e ação em contextos escuros validados. |

A cor institucional não deve ocupar todos os planos. A maior parte da interface usa superfícies neutras; a cor conduz seleção, ação e orientação. Gráficos usam poucas séries e legenda textual. Vermelho, âmbar e verde de estado ficam reservados ao significado da operação, independentemente da marca do cliente.

### Contrastes medidos e uma correção necessária

| Par sólido | Razão aproximada | Leitura |
| --- | --- | --- |
| Branco sobre #007F86 | 4,795:1 | Atende 4,5:1 para texto normal nesse par. |
| #007F86 sobre #F4F7FA | 4,459:1 | Não atende 4,5:1 para texto normal. Não arredondar para aprovar. |
| #00666D sobre #F4F7FA | 6,257:1 | Alternativa proposta para links. |
| #102A43 sobre #F4F7FA | 13,618:1 | Base de leitura com margem confortável. |
| Branco sobre #102A43 | 14,642:1 | Base para conteúdos reversos. |

Os cálculos valem para os pares exatos. Não aprovam uma página inteira, transparências, imagens, hover ou overlays. O pacote inclui relatório reproduzível por par. [A01–A03]

### Cores de estado propostas

| Estado | Texto claro / fundo claro | Texto escuro / fundo escuro | Mensagem típica |
| --- | --- | --- | --- |
| Sucesso | #17633D / #EAF5EE | #8FDDB2 / #1C392D | Permitido; confirmado; conciliado. |
| Atenção | #8A4B08 / #FFF4DF | #FFD18A / #43321C | Em análise; aguardando aprovação. |
| Erro | #B42318 / #FFF0EE | #FFB4AD / #42272C | Negado; falha; revogado. |
| Informação | #2459A6 / #EDF3FF | #A8C8FF / #213653 | Orientação; revisão disponível. |
| Indisponível | Texto secundário + superfície neutra | Texto secundário + superfície neutra | Sem comunicação; dado desatualizado. |

Sempre combinar cor com texto, ícone quando útil e estado acessível. “Visitante” é um vínculo, não um aviso amarelo por definição. “Sem biometria” pode ser uma condição normal quando existe outra credencial autorizada. Não colorir pessoas como risco sem uma regra ou ocorrência que justifique o estado.

### Impressão

HEX e RGB são a referência digital desta versão. Para impressão, gerar arquivos com o perfil de cor solicitado pela gráfica e fazer prova no papel escolhido. Não declarar equivalência Pantone ou CMYK universal a partir de uma conversão automática. Entregar vetor para placas, adesivos e materiais de alta dimensão; PNG serve a aplicações raster compatíveis.

## 6 Tipografia

Manter Lato como família principal na primeira etapa. A web já importa pesos 400 e 700. Usar 400 no corpo e 700 em títulos, ações e destaques. Só adicionar um peso após disponibilizar o arquivo real e demonstrar uma necessidade visual; não usar uma sequência de pesos inexistentes para diferenciar cada card. [R02, F01]

| Estilo | Tamanho proposto | Peso | Linha | Uso |
| --- | --- | --- | --- | --- |
| Título de página | 28 px desktop / 24 px celular | 700 | 1,2–1,3 | Nome do contexto de trabalho. |
| Título de seção | 18–20 px | 700 | 1,35 | Grupos de conteúdo. |
| Corpo principal | 16 px confortável / 14 px denso | 400 | 1,5 | Instruções e leitura. |
| Tabela | 13–14 px | 400/700 | 1,4 | Dado operacional com alinhamento. |
| Campo e botão | 14–16 px confortável | 400/700 | 1,4 | Preenchimento e ações. |
| Metadado | 12 px como meta | 400 | 1,4 | Hora, origem e contexto secundário. |
| Indicador | 28–36 px | 700 | 1,1 | Valor com unidade e período. |

O protótipo usa alguns rótulos compactos de 11 px como hipótese visual; a implementação deve testar legibilidade com operadores e adotar a meta de 12 px nos metadados relevantes. Não reduzir informação crítica para acomodar mais colunas.

Usar numerais tabulares em horários, valores e colunas numéricas. Alinhar valores monetários à direita e informar unidade, competência e base de cálculo. Reservar caixa alta a rótulos curtos de navegação, nunca a parágrafos. Evitar títulos longos truncados sem forma de acessar o texto completo.

A Lato é distribuída sob SIL Open Font License 1.1; preservar os avisos ao distribuir os arquivos. Os binários do pacote vieram do repositório Google Fonts e incluem a licença. Inter aparece no caderno como alternativa de avaliação futura, não como fonte atual ou substituição automática. [F01, F02]

## 7 Espaçamento, superfícies e ícones

Usar uma escala base de 4 px: 4, 8, 12, 16, 20, 24, 32, 40, 48 e 64. Escolher o espaçamento pelo relacionamento: campos de um grupo ficam próximos; grupos diferentes recebem mais espaço. Evitar espaçamentos isolados de 13, 19 ou 27 px sem uma necessidade óptica documentada.

Proposta: radius 6–8 px em controles, 10–12 px em painéis e 14–16 px em modais. A base atual usa cards de 16 px e controles de 10 px; a redução no protótipo é uma hipótese para aumentar precisão visual. Aprovar um conjunto final e aplicá-lo pelo token. Sombras leves servem a superfícies elevadas; bordas organizam o conteúdo normal.

Ícones usam grade 24×24, traço consistente entre 1,5 e 2 px e renderização de 20–24 px conforme o controle. O produto já possui SVGs locais: consolidá-los em um componente único. Ícone isolado precisa de nome acessível e, quando necessário, tooltip disponível também por foco. Não usar emojis como ícones operacionais.

Um componente externo de ícones pode ser adotado por decisão técnica. Lucide é uma alternativa referenciada, com licença e avisos próprios; o protótipo usa desenhos SVG locais e não depende dessa instalação. [F03]

## 8 Estrutura de aplicação e navegação

O layout recomendado é um shell de aplicação: seletor de contexto, navegação lateral, cabeçalho com localização e busca, área de trabalho e feedback próximo da ação. A página começa pelo título e pelas decisões do usuário, sem uma seção promocional grande ocupando a área operacional.

| Região | Conteúdo | Regra |
| --- | --- | --- |
| Identidade | Logo e nome do condomínio | Legíveis e proporcionais; nunca confundir a marca com o tenant ativo. |
| Navegação | Módulos do perfil | Agrupar por tarefa e destacar a página atual. |
| Cabeçalho | Contexto, busca, notificações, usuário | Manter ações globais consistentes. |
| Título da página | Nome, descrição curta, ação principal | Uma ação principal por contexto. |
| Conteúdo | Filas, tabela, formulário, calendário ou indicadores | Escolher a representação pela tarefa. |
| Feedback | Validação, atualização, erro e resultado | Informar o que aconteceu e o próximo passo. |

O protótipo usa sidebar de 236 px e cabeçalho de 72 px; abaixo de 1024 px, o menu vira gaveta. O código atual usa 244 px e variação de 215 px. Esses números são decisões a consolidar, não motivos para reescrever rotas. Manter a boa gestão de foco já existente no shell auditado.

Nomes de módulos devem ser estáveis: Pessoas e unidades; Áreas e acessos; Reservas; Financeiro; Comunicação; Configurações. Dentro de cada módulo, usar abas ou filtros locais. Não criar um item lateral para cada ação pequena. Busca e atalhos complementam a navegação visível.

## 9 Dashboards por responsabilidade

| Perfil | Pergunta central | Conteúdo prioritário | Ação principal |
| --- | --- | --- | --- |
| Gestor | O que precisa de decisão hoje? | Pendências, reservas, solicitações, ocupação e financeiro autorizado. | Revisar ou resolver uma pendência. |
| Portaria | Quem está chegando e o que posso executar? | Fila, pessoa, vínculo, unidade, validade, local e dispositivo. | Atender e confirmar uma operação. |
| Morador | O que preciso fazer no meu condomínio? | Convite, reserva, encomenda, cobrança própria e avisos. | Executar uma tarefa pessoal. |
| MASTER plataforma | Quais clientes e integrações precisam de atenção? | Carteira, implantações, homologações, revisões e incidentes. | Abrir o escopo correto e investigar. |
| MASTER implantação | O local está configurado e testado? | Dispositivos, mapeamentos, conectividade e evidências técnicas. | Validar ou corrigir implantação no tenant autorizado. |

Indicadores devem exibir período, origem, atualização e caminho até os registros que os explicam. “Acessos hoje” precisa esclarecer se conta tentativas, pessoas únicas ou eventos autorizados. “Recebido” e “saldo” exigem competência e fórmula. Não apresentar tendência positiva sem uma base comparável.

Ações urgentes devem vir antes de gráficos de resumo. Gráficos respondem perguntas de volume, tendência ou composição. Preferir barras e linhas; evitar medidores decorativos, gráficos 3D e séries sem legenda. Oferecer tabela alternativa para o conteúdo numérico. [D02, D04]

## 10 Componentes e estados

### Botões

A ação primária usa preenchimento institucional e texto validado. A secundária usa superfície neutra com borda de controle. A terciária é um link ou botão discreto. A ação destrutiva usa semântica de erro e explica o efeito. Texto de botão descreve o verbo: Salvar rascunho, Publicar regra, Revogar autorização, Confirmar retirada.

Estados obrigatórios: normal, hover, foco, pressionado, carregando e indisponível. Durante o envio, impedir duplicidade sem apagar o contexto. O resultado deve refletir a confirmação recebida, não apenas a conclusão de uma animação.

### Formulários

Rótulos ficam visíveis acima dos campos; placeholder não substitui label. Indicar obrigatoriedade de modo consistente, instruções perto do campo e erro textual associado por acessibilidade. Preservar os valores já preenchidos quando houver falha. Resumo de erros deve levar ao campo correspondente. Fluxos longos podem ser divididos em etapas com progresso e revisão final.

Validações de negócio pertencem ao servidor. O frontend pode antecipar mensagens, mas não autoriza uma pessoa por idade, reserva ou vínculo apenas porque a tela aceitou os valores. A mesma regra vale para alterações de marca e de domínio.

### Tabelas e listas

Usar tabelas quando a comparação entre colunas é a tarefa. Incluir título, busca, filtros, contagem e paginação quando necessário. Cabeçalhos devem nomear o dado; números ficam alinhados; ações da linha permanecem associadas ao registro. Ordenação, seleção e expansão precisam de estado acessível. Ações em lote mostram quantidade e escopo antes da confirmação. [D04]

No celular, uma lista com os campos essenciais costuma funcionar melhor que seis colunas comprimidas. Manter uma forma de consultar detalhes e filtros. O calendário oferece alternativa em lista e não depende de arrastar para operar. [D03, A01]

### Feedback e falhas

| Estado | Apresentação mínima |
| --- | --- |
| Carregando | Esqueleto compatível com a estrutura, sem conteúdo falso que pareça dado real. |
| Vazio inicial | Explicar o que aparecerá e oferecer a primeira ação permitida. |
| Busca sem resultado | Informar filtro ativo e permitir limpar ou ajustar. |
| Erro recuperável | Dizer qual operação falhou, preservar dados e oferecer nova tentativa. |
| Sem permissão | Explicar o limite de acesso sem expor informações privadas. |
| Dados desatualizados | Mostrar última atualização e restrições de ação. |
| Sucesso parcial | Identificar itens concluídos e pendentes, sem um “sucesso” genérico. |

Toast informa uma conclusão simples. Erro que bloqueia formulário fica dentro do contexto de edição, com texto persistente e foco adequado. Dialog exige título, fechamento por teclado quando permitido, retenção de foco e retorno ao controle que o abriu. Não empilhar modais de confirmação indefinidamente.

## 11 Semântica visual do controle de acesso

O produto deve distinguir identidade reconhecida, autorização calculada, comando enviado, resposta do equipamento e estado físico. Esses eventos não são sinônimos. O visual usa rótulos explícitos e uma sequência de evidências; o verde não pode encobrir uma confirmação que não existe.

| Etapa | Estado possível | O que a tela pode afirmar |
| --- | --- | --- |
| Identificação | Correspondência encontrada | Uma credencial foi associada a uma pessoa. |
| Regra | Permitido, negado ou revisão | A política e o contexto produziram uma decisão. |
| Distribuição | Sincronizado, pendente ou falha | O dispositivo recebeu ou ainda não recebeu a configuração. |
| Comando | Enviado, aceito, rejeitado ou expirado | O canal processou a solicitação no nível informado. |
| Sensor | Aberto, fechado ou desconhecido | Há leitura física compatível; sem sensor, não declarar abertura confirmada. |

Em ações manuais, manter visíveis pessoa, condomínio, área, direção e ponto de abertura. Confirmações devem repetir o local, especialmente quando existem vários relés ou portas próximos. A interface não deve oferecer um botão genérico “Liberar” sem dizer o que será liberado.

Regras de faixa etária usam data de nascimento validada e data/fuso da operação, nunca estimativa de idade pela imagem facial. Se uma área requer 18 anos e a idade está ausente, o fluxo segue a política de revisão/negação prevista; não presume elegibilidade. Uma área “somente visitantes” não permite morador por herança implícita. Uma reserva não supera uma proibição de visitante. Esses exemplos precisam de testes de domínio além do protótipo.

As regras são responsabilidade do condomínio dentro das permissões delegadas. Homologação, credenciais de integração, comunicação e mapeamento físico pertencem ao MASTER e à implantação autorizada. O gestor precisa ver funcionamento e impacto, sem receber senhas, chaves ou controles que possam interromper a infraestrutura.

## 12 Responsividade e acessibilidade

A meta de implementação é WCAG 2.2 AA nos fluxos concluídos. O pacote de design não constitui certificação. Além de cor, verificar teclado, foco, estrutura semântica, nome acessível, zoom, leitor de tela, mensagens de status e alternativas a gestos. [A01]

| Critério | Referência normativa | Decisão CondPulse |
| --- | --- | --- |
| Texto comum | 4,5:1, com condições e exceções da norma | Validar pares exatos, sem arredondar falhas. |
| Texto grande | 3:1 segundo a definição WCAG | Não usar o limiar menor para todo título por conveniência. |
| Informação não textual necessária | 3:1 contra cores adjacentes | Aplicar a controles e estados essenciais, não a todo divisor decorativo. |
| Alvo AA | 24×24 CSS px com exceções | Projetar 48 px para toque e portaria; compacto de desktop exige cuidado com área clicável. |
| Foco | Visível e não totalmente oculto, conforme critérios aplicáveis | Anel contrastante com offset sobre superfície previsível. |
| Reflow e zoom | Critérios de redimensionamento/reflow da norma | Testar 320 CSS px e zoom 200%/400%, respeitando exceções aplicáveis. |

O critério 2.4.13 Focus Appearance é AAA. A medida ampliada de alvo 44×44 é referência AAA; 48 px é a escolha interna proposta para conforto operacional. Essas classificações não devem ser confundidas com obrigação universal AA. [A01, A04]

Testar pelo menos 360/390 px no celular, 768 px, 1024 px, 1440 px e 1920 px, além de zoom e conteúdo longo. A sidebar recolhida deve sair da ordem de foco. A gaveta aberta precisa de fechamento, backdrop e foco coerente. Densidade compacta não deve reduzir os alvos em uma operação por toque.

Modo escuro usa tokens próprios. Não aplicar filtro de inversão ao documento. Conferir logos, superfícies elevadas, gráficos, campos, seleção, avisos e foco. Preferência “seguir sistema” deve ser resolvida sem mostrar por instantes a marca ou o tema de outro condomínio.

## 13 Aplicação em mídia e materiais

Os templates de campanha acompanham o kit em SVG editável e PNG. São exemplos visuais originais, com o logotipo CondPulse preservado; as imagens de concorrentes do caderno não fazem parte dos materiais promocionais.

| Peça | Arquivo de referência | Regras |
| --- | --- | --- |
| Feed vertical | 1080×1350 | Mensagem curta, logo protegido e uma ação. |
| Story | 1080×1920 | Reservar margens de interface; testar no canal antes de publicar. |
| Anúncio horizontal | 1200×628 | Hierarquia reconhecível em redução e texto conciso. |
| Assinatura de e-mail | 600×180 | Nome e contato podem ser texto HTML; imagem não deve ser a única informação. |
| Avatar | Símbolo em placa | Centralizar com margem e testar recorte circular. |
| Documento | Logo horizontal em fundo claro | Identificação, data, autoria e revisão conforme o propósito. |

Os tamanhos são formatos de trabalho propostos, não garantia de atendimento a todas as políticas atuais de cada plataforma. Na publicação, verificar a especificação do canal. Não adicionar URL, telefone ou promessa comercial que não tenham sido aprovados. Na assinatura de e-mail, usar endereço e contato reais somente quando fornecidos pela operação.

Em fotografias, priorizar situações reais do condomínio, espaços e tarefas; evitar usar rosto de pessoa como sugestão de cadastro biométrico ou de autorização. Imagens e depoimentos devem ter origem e permissão apropriadas. A linguagem de publicidade pode ter mais espaço e contraste expressivo; a interface operacional mantém foco no conteúdo de trabalho.

## 14 Integração com o sistema existente

A evolução deve entrar por mudanças pequenas e rastreáveis. Mapear os tokens atuais para os nomes novos; migrar shell e componentes de maior uso; testar as quatro áreas de perfil; só depois remover os estilos antigos. Os nomes de rotas e as regras de autorização existentes não precisam mudar para receber a nova aparência.

| Etapa | Entrega | Condição de conclusão |
| --- | --- | --- |
| 1 Identidade | Marca única, favicon e ícone mobile alinhados | Ativos aprovados e renderizados nas superfícies previstas. |
| 2 Fundação | Tokens, tipografia, foco e semântica | Pares/estados verificados e consumo real no código. |
| 3 Componentes | Botão, campo, tabela, aviso, modal e navegação | Estados completos, teclado e regressão visual. |
| 4 Telas | Gestão, portaria, morador e MASTER | Fluxos reais preservados; informações e ações corretas. |
| 5 White label | Perfis, revisões, editor, validação e publicação | Isolamento, reversão, cache e permissões testados. |
| 6 Canais | Documentos, e-mail, domínio e apps quando contratados | Verificação específica por canal e operação. |

Os tokens JSON e CSS fornecidos são contratos de referência. O schema de marca valida a forma dos dados, não a autorização nem a propriedade dos assets. A implementação precisa conectar o resolver ao contexto autenticado e aos serviços atuais. Não executar o JSON como CSS ou HTML livre.

### Critérios para aceitar a evolução visual

| ID | Verificação | Evidência esperada |
| --- | --- | --- |
| UI-01 | Mesma identidade nas superfícies escolhidas | Capturas por perfil, favicon e build mobile. |
| UI-02 | Teclado, foco e mensagens operáveis | Roteiro manual com leitor de tela/teclado e correções documentadas. |
| UI-03 | Semântica de acesso preservada | Casos permitido, negado, pendente, comando sem sensor e offline. |
| UI-04 | Conteúdo longo e telas pequenas | Nomes longos, valores grandes, 320 CSS px e zoom. |
| UI-05 | Temas claro/escuro e marcas distintas | Matriz de pares e telas, incluindo estados de falha. |
| UI-06 | Nenhuma regressão operacional | Testes reais de cadastro, reserva, atendimento e escopo MASTER. |
| UI-07 | Dados sensíveis minimizados | Revisão de telas, logs, exportações e acessos por perfil. |
| UI-08 | Sem perda de desempenho material | Comparação de carregamento e resposta com baseline real. |

Executar piloto com usuários dos perfis envolvidos, observar tempo para concluir tarefas, erros de seleção de local, entendimento de status e necessidade de ajuda. As metas quantitativas devem partir de uma medição do CondPulse atual. Não declarar redução de tempo ou aumento de conversão sem essa comparação.

## 15 Governança da identidade

O MASTER CondPulse mantém o design system, as variantes oficiais, a política de personalização e a homologação técnica. O responsável pela identidade do cliente mantém seus assets e solicita ou publica revisões conforme a capacidade recebida. O gestor do condomínio define regras e cadastros locais; essa autonomia não inclui segredos ou configuração física de integração.

Cada alteração do padrão precisa de versão, motivo, componentes afetados, migração necessária e evidências de teste. Uma mudança de logo não deve trocar silenciosamente o histórico documental. Uma mudança de cor não deve reclassificar estados. Uma mudança de fonte exige revisar quebra de linha, alinhamento e carregamento.

O caderno complementar mostra referências de mercado e pranchas navegáveis. O anexo a seguir detalha o white label, com modelo de dados, herança, cache, publicação, rollback e critérios de aceite. A entrega fica pronta para revisão de produto e planejamento de implementação, sem afirmar que os itens propostos já foram implantados.

## Fontes do manual

R01. Manifesto web no commit auditado: https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/package.json

R02. Fontes importadas: https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/main.tsx

R03. Componentes e marca: https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/primitives.tsx

R04. CSS atual: https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/styles.css

R05. Marca vetorial e guia: https://github.com/advancedgit-alt/Condpulse-v2.0/tree/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design

R06. Configuração mobile: https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/app.json

A01. W3C WCAG 2.2: https://www.w3.org/TR/WCAG22/

A02. Contraste de texto: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html

A03. Contraste não textual: https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html

A04. Tamanho mínimo de alvo: https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum

F01. Licença Lato: https://github.com/latofonts/lato-source/blob/master/LICENSE.txt

F02. Arquivos Lato distribuídos por Google Fonts: https://github.com/google/fonts/tree/main/ofl/lato

F03. Licença Lucide, alternativa opcional: https://lucide.dev/license

D01. Linear, organização e temas: https://linear.app/now/behind-the-latest-design-refresh

D02. Stripe Dashboard: https://docs.stripe.com/dashboard/basics

D03. Shopify Polaris, tabelas: https://shopify.dev/docs/api/app-home/latest/web-components/layout-and-structure/table

D04. Carbon, data table: https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines

D05. Atlassian, design tokens: https://atlassian.design/tokens/design-tokens/

S01. OWASP, isolamento de tenants: https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html

Consulta das fontes: 31/10/2026. As recomendações de produto deste manual são propostas CondPulse fundamentadas no contexto e nas referências; não são declarações de implementação de terceiros.
