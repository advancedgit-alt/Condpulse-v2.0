# CondPulse — referências de mercado para interface e identidade

**Data da pesquisa:** 31/10/2026. **Escopo:** fontes oficiais públicas; seis referências do mercado condominial brasileiro. Documento de pesquisa para apoiar os manuais, sem constituir manual de identidade final nem auditoria de produtos autenticados.

## 1. Conclusão de design

A combinação mais útil para CondPulse é: rotinas de reservas da TownSq e da uCondo; acesso rápido da portaria no Condomob; organização de carteira e contexto da unidade da Superlógica; comunicação financeira direta da CondoConta; personalização de interface divulgada pela MyCond. São referências de problemas e fluxos, não uma indicação para replicar a aparência de qualquer marca.

Preservar a direção própria já estabelecida para CondPulse — navy `#102A43` e teal `#007F86` — e aplicar a cor de marca principalmente em navegação ativa, ações primárias e elementos de identidade. Estados operacionais devem continuar reconhecíveis em qualquer tema de cliente. A definição desses estados e o contraste necessário pertencem ao design system CondPulse, não devem ser inferidos das cores dos concorrentes.

Os produtos abaixo foram selecionados pela relevância das funções e pela disponibilidade de evidência pública. **Não são um ranking de participação de mercado, qualidade, preço ou segurança.**

## 2. Como interpretar a evidência

- **Tutorial operacional público:** o fornecedor documenta telas e passos de uma tarefa. É evidência forte para navegação e fluxo, sem provar desempenho, acessibilidade ou comportamento em todas as versões.
- **Imagem de produto publicada:** existe uma imagem ou animação atribuída pelo fornecedor ao sistema. Serve como referência visual; pode ser de uma versão anterior ou um mockup.
- **Página comercial:** confirma o posicionamento e as funções anunciadas. Não permite concluir como funciona a interface autenticada.
- **Inferência CondPulse:** recomendação original deste estudo. Não é uma característica atribuída ao concorrente.

As imagens foram localizadas através de links oficiais. Não foram acessadas contas privadas. Após a pesquisa inicial, foram baixados e inspecionados quatro exemplos de UI: TownSq, Condomob, MyCond e Superlógica/Gruvi. A auditoria complementar está em `market_assets/visual_audit.md`, com arquivos e atribuições em `market_assets/attribution.json`. As descrições de cor são qualitativas: não foram medidos contraste, tipografia ou hex oficiais. Duas imagens adicionais, da CondoConta e da landing uCondo, são fotografias/composições comerciais sem UI e foram excluídas do caderno de telas.

## 3. Matriz: o que aproveitar e o que evitar

| Referência | Melhor uso no estudo | Evidência pública | Aproveitar no CondPulse — proposta | Evitar no CondPulse — proposta |
|---|---|---|---|---|
| TownSq | Reserva de áreas comuns e comunicação ao morador | Tutorial web com calendário e aprovação; tutorial app; kit de telas anunciado | Área → data → detalhe → decisão; status acompanhado de texto; confirmação e histórico | Depender apenas de um ponto colorido para distinguir status; transportar o menu do aplicativo sem adaptação ao desktop |
| Superlógica / Gruvi | Operação da administradora e visão de vários condomínios | Manual público com navegação lateral, resumo de implantação e GIFs | Contexto do condomínio sempre visível; visão de carteira; resumo com acesso à lista detalhada | Misturar banners de divulgação e ações críticas na mesma hierarquia; fazer o usuário perder o condomínio ativo |
| Condomob | Portaria e ações recorrentes | Manual público da tela inicial e imagem; página administrativa e tutoriais | Busca rápida por pessoa/placa, nova encomenda, troca de turno, fila de avisos/reservas | Entregar ao porteiro uma home gerencial cheia de gráficos ou permitir que ações técnicas globais fiquem entre tarefas de balcão |
| uCondo | Reservas com revisão e regras; organização modular | Tutorial web com GIFs; vídeos de demonstração ligados pela página oficial; manual de marca | Formulário em sequência curta; resumo antes da confirmação; regras no contexto; módulos por objetivo | Utilizar a landing page como modelo de produto; exigir o mesmo caminho longo de uso casual para um operador que executa a tarefa dezenas de vezes |
| CondoConta | Financeiro condominial | Página comercial e passos de abertura; a imagem examinada é fotografia, sem UI | Saldo, período e movimentação com hierarquia clara; leitura por status; explicar cada valor e ação | Tratar cor verde de marca como prova de resultado positivo; supor que screenshot comercial valida conciliação, permissões ou integração |
| MyCond | Personalização por condomínio; home do morador; carteira administrativa | Página de atualização com Home/Login e GIF de reorganização; changelog com tela de carteira | Identidade do cliente nos acentos; atalhos pessoais; visibilidade de estados; carteira acionável | Permitir que personalização altere as cores de bloqueio/erro; misturar escolhas pessoais de atalhos com permissões; presumir isolamento de dados a partir da aparência |

## 4. Fichas de referência

### 4.1 TownSq — reservas e comunicação

**O que a fonte confirma.** O tutorial de aprovação web descreve navegação lateral em Reservas → Dependências, seleção da área, indicador no calendário e ação de aprovação. A confirmação é comunicada por e-mail. A página institucional oferece um press kit e um conjunto de mockups/telas; os downloads exigem formulário. Não foram preenchidos dados nem solicitado contato. [T1–T4]

**Aplicação proposta.** Na CondPulse, usar a sequência área/data/reserva como modelo de tarefa e mostrar estado em palavra e ícone, além da cor. A área deve trazer capacidade, regras de uso, aprovação necessária e horário. Reservar e permitir entrada física são decisões distintas: o detalhe deve esclarecer qual permissão foi criada, para quem, quando e por qual regra.

**Cor e identidade.** A captura inspecionada mostra verde vivo em marca/ação principal, verde acinzentado em textos e marcadores, branco e cinza claro. O tutorial também menciona marcador bordô em outra etapa. São observações de tela, não paleta oficial. O press kit é a fonte apropriada para a representação da marca.

**Links visuais.**

- [T1 — tutorial de aprovação web](https://suporte.townsq.com.br/reservas-como-aprovar-uma-reserva-pela-web).
- [T2 — PNG indicado no primeiro passo](https://suporte.townsq.com.br/hubfs/Knowledge%20Base%20Import/AAo-OhNEUONhK1h1BsV8R.png). Legenda sugerida: “TownSq: exemplo público de navegação para reservas; fonte: Central de Ajuda TownSq”.
- [T3 — página de mockups/telas, com formulário](https://condominio.townsq.com.br/townsq-telas).
- [T4 — press kit, com formulário](https://condominio.townsq.com.br/townsq-press-kit-br).
- [T5 — página institucional que encaminha aos kits](https://www.home.townsq.com.br/sobre-nos/).

### 4.2 Superlógica / Gruvi — contexto, carteira e navegação

**O que a fonte confirma.** O manual de acesso ao Dashboard Gruvi, atualizado em 08/10/2026, descreve um menu lateral e uma prévia da quantidade de condomínios com ou sem grupos criados. A entrada para o painel aparece no contexto da Área do Condômino e também das Unidades. O artigo fornece duas animações. [S1–S2]

**Aplicação proposta.** Para a CondPulse, distinguir a carteira MASTER da visão operacional de um condomínio. Toda tela deve manter o nome do condomínio, o perfil efetivo e o ambiente. Uma lista de condomínios pode mostrar implantações pendentes, integração saudável/degradada e pendências que precisam de ação. Cada indicador deve abrir a lista que o explica. Para o gestor, esconder configurações técnicas fora de sua competência e oferecer diagnóstico legível.

**Cor e identidade.** Os quadros inspecionados mostram navegação carvão, marca ciano, base clara, etiquetas verdes, ações amarelas e ajuda roxa. São observações de uma demonstração de arquivo: o quadro inicial exibe data de 2022, apesar da atualização do artigo em 2026. Não tratá-las como especificação de marca atual.

**Links visuais.**

- [S1 — Dashboard Gruvi: tutorial oficial](https://condominios.superlogica.com/hc/pt-br/articles/10440036697623-Como-acessar-a-Dashboard-Gruvi).
- [S2 — GIF oficial ligado no artigo](https://condominios.superlogica.com/hc/article_attachments/10436431826199). Legenda sugerida: “Superlógica: caminho público de acesso ao Dashboard Gruvi; fonte: Central de Ajuda Superlógica”. GIF baixado e quadros inspecionados; o quadro 117 está disponível em `market_assets/superlogica_gruvi_frame_117.png`. Material de arquivo; não comprova aparência atual.
- [S3 — tutorial de consulta da unidade](https://condominios.superlogica.com/hc/pt-br/articles/41295968786583-Como-consultar-as-informa%C3%A7%C3%B5es-de-uma-unidade); adequado para aprofundamento, não inspecionado nesta ficha.

### 4.3 Condomob — portaria como estação de trabalho

**O que a fonte confirma.** A documentação da tela inicial da portaria lista consultas por documento/nome/placa, cadastro de encomenda, ocorrências e troca de turno. Também descreve áreas de acesso às últimas mensagens, encomendas, ocorrências e às próximas reservas e atividades. A página administrativa apresenta gestão centralizada de condomínios e tutoriais públicos. [C1–C3]

**Aplicação proposta.** Este é o benchmark funcional mais direto para a estação da portaria CondPulse: busca persistente, identificação da pessoa, motivo da visita, unidade de destino, estado da autorização e ação autorizada. Acrescentar atualização de dados visível, horário da última sincronização e distinção entre “autorização válida” e “abertura confirmada pelo dispositivo”. A tela do porteiro deve permitir atender a fila; o diagnóstico técnico detalhado pertence ao MASTER.

**Cor e identidade.** A captura da portaria mostra navegação carvão, cabeçalho cinza escuro, ações verdes e troca de turno laranja. Há tabelas claras e estados vazios amarelos. São observações, não valores oficiais. A captura inclui datas de 2024; a atualização do manual não comprova atualização da imagem. A imagem administrativa C4 é de arquivo (caminho de 2021).

**Links visuais.**

- [C1 — guia da tela inicial da portaria](https://suporte.condomob.net/hc/pt-br/articles/49712719681435-Guia-de-Navega%C3%A7%C3%A3o-na-Tela-Inicial-da-Interface-da-Portaria).
- [C2 — imagem oficial do guia](https://suporte.condomob.net/hc/article_attachments/49712795461275). Legenda sugerida: “Condomob: tela pública de referência para a interface da portaria; fonte: Central de Ajuda Condomob”.
- [C3 — painel administrativo e vídeos](https://condomob.net/painel-administrativo-sistema-condominio/).
- [C4 — imagem administrativa de arquivo](https://condomob.net/wp-content/uploads/2021/09/Gerenciamento-de-condominio-para-administracao.png). Marcar “imagem publicada em página institucional; referência de arquivo”.

### 4.4 uCondo — tarefa guiada e manual de marca público

**O que a fonte confirma.** O tutorial de reservas web usa menu à esquerda, escolha de dependência/data/horário, revisão com observação e aceite dos termos, quando existirem. A página de produto encaminha a duas demonstrações em vídeo, uma financeira e outra de comunicação. O site disponibiliza um Manual de Aplicação de Marca, cujo link aponta para um PDF nomeado como versão 2026. [U1–U5]

**Aplicação proposta.** Usar o princípio de revisão explícita antes de confirmar uma operação. Na CondPulse, anexar à revisão as regras que influenciaram o resultado, com linguagem clara: “visitantes não autorizados para esta área”, “idade mínima definida pelo condomínio”, “liberação válida até 22h”. Configurações devem mostrar a diferença entre rascunho e publicada. Uma versão compacta deve servir às rotinas repetidas de portaria.

**Cor e identidade.** O próprio arquivo do logo na central é identificado como roxo. O manual oficial é a fonte apropriada para especificações; o PDF foi localizado, mas seus valores de cor e fontes não foram extraídos. Não declarar um hex oficial.

**Links visuais.**

- [U1 — reserva web com GIFs](https://suporte.ucondo.com.br/reservas-como-criar-uma-reserva-via-web). Legenda sugerida: “uCondo: sequência pública para reservar uma área comum; fonte: Central de Ajuda uCondo”.
- [U2 — página oficial com demonstrações](https://www.ucondo.com.br/condominios/aplicativo-de-condominios).
- [U3 — vídeo financeiro](https://www.youtube.com/watch?v=8XrFH5uCHcM), URL derivada do embed oficial presente em U2; vídeo não assistido nesta pesquisa.
- [U4 — vídeo de comunicação](https://www.youtube.com/watch?v=AMLaBwYAbT4), URL derivada do embed oficial presente em U2; vídeo não assistido nesta pesquisa.
- [U5 — manual da marca](https://www.ucondo.com.br/manual-da-marca).
- [U6 — PDF de marca vinculado oficialmente](https://drive.google.com/file/d/1MaTgYsjFKDUnsX1_MN3WfCC67TiEP5PK/view).

### 4.5 CondoConta — leitura financeira focada

**O que a fonte confirma.** A página de conta bancária anuncia pagamentos, demonstrativo financeiro, cotas e prestação de contas, além de acesso por aplicativo e web. Explica as etapas de abertura de conta. A imagem Banking-1.webp foi inspecionada e mostra uma pessoa usando tablet, sem interface. Portanto, a CondoConta permanece referência funcional/comercial, sem tela de software validada nesta pesquisa. [F1–F2]

**Aplicação proposta.** Para os módulos financeiros da CondPulse, separar valor, período, origem e estado. Saldos e totais devem conduzir à movimentação que os compõe. Pagamentos e operações bancárias exigem confirmação contextual e retorno de processamento. Usar tabelas de movimentações, filtros legíveis, totais alinhados e estados documentados; reservar gráficos para perguntas claras. São recomendações de design, não alegações de uma auditoria da interface CondoConta.

**Cor e identidade.** A paleta da marca não foi verificada neste levantamento. Não confundir a identidade visual do banco com o significado semântico dos valores financeiros.

**Links visuais.**

- [F1 — página oficial da conta](https://condoconta.com.br/conta-bancaria/).
- [F2 — fotografia comercial, sem UI](https://condoconta.com.br/wp-content/uploads/2024/05/Banking-1.webp). **Excluir do caderno de telas de software.** Inspeção confirmou pessoa com tablet, sem interface visível.

### 4.6 MyCond — personalização e carteira acionável

**O que a fonte confirma.** A página do novo aplicativo declara personalização de cores por condomínio e reorganização dos módulos da Home, com imagens de Home/Login e uma animação. O changelog de 10/04/2026 descreve a tela Minha Carteira com condomínios, estados de geração de boletos e ações diretas. Há imagens públicas também para ocorrências e encomendas. [M1–M4]

**Aplicação proposta.** O white-label CondPulse pode permitir logo e cor de acento por cliente, com blocos reordenáveis por usuário. A marca do condomínio pertence à configuração do tenant; a ordem de atalhos pertence à preferência pessoal; os recursos disponíveis pertencem às permissões. Essas três decisões devem permanecer independentes. A personalização visual não pode substituir autorização em servidor nem alterar os significados de alerta, bloqueio ou falha.

**Cor e identidade.** O mockup inspecionado mostra navy, branco/cinza claro, símbolo amarelo e contadores vermelho/coral. A fonte confirma cores de destaque por condomínio: esse exemplo não representa todos os clientes. Isso sustenta estudar tokens por tema na CondPulse; a aparência não comprova isolamento técnico ou white-label integral.

**Links visuais.**

- [M1 — novo aplicativo](https://mycond.com.br/novo_mycond/).
- [M2 — Home e Login](https://mycond.com.br/wp-content/uploads/2026/06/hero-1.png). Legenda sugerida: “MyCond: Home e Login divulgados na apresentação do novo app; fonte: MyCond”.
- [M3 — reorganização da Home](https://mycond.com.br/wp-content/uploads/2026/06/app-reorganizar.gif), formato GIF; link oficial resolvido, não renderizado pela ferramenta.
- [M4 — changelog com carteira e ocorrências](https://mycond.com.br/suporte/).
- [M5 — leitura de etiqueta de encomenda](https://mycond.com.br/wp-content/uploads/2026/06/myia-encomendas-2-telas.png). Legenda sugerida: “MyCond: telas de leitura de etiqueta publicadas pelo fornecedor; fonte: MyCond”.
- [M6 — demonstração da Minha Carteira](https://mycond.com.br/wp-content/uploads/2026/04/Adobe-Express-3-WhatsApp-Video-2026-04-09-at-17.40.14.gif), GIF ligado em M4.

## 5. Referência removida da seleção atual: NokNox

A página oficial [noknox.com](https://noknox.com/) informa encerramento das atividades e direcionamento para MyCond. A referência foi encontrada em busca oficial; a abertura direta posterior teve timeout. Há tutoriais antigos acessíveis, mas isso não confirma operação atual. Para uma comparação histórica, citar como produto encerrado segundo o aviso oficial; para o manual atual, usar MyCond.

## 6. Padrões originais para as pranchas CondPulse

Estas propostas sintetizam o problema do usuário e as tarefas documentadas; não descrevem telas privadas das empresas.

| Prancha CondPulse | Conteúdo principal | Relação com a pesquisa |
|---|---|---|
| MASTER da plataforma | Carteira, homologações, conectividade, falhas, publicação de versões e auditoria | Carteira/administração em Superlógica e MyCond; responsabilidades exclusivas definidas pelo usuário |
| Gestor do condomínio | Pendências, reservas, cadastros, regras de área e histórico de alterações | Gestão de reservas em TownSq/uCondo; módulos e contexto da unidade |
| Portaria | Busca, fila de chegada, encomendas, resultado da regra, abertura e retorno do equipamento | Ações rápidas e próximos compromissos em Condomob; requisitos operacionais CondPulse |
| Áreas e regras | Tabela por área; público elegível; horários; idade; visitante; estado da publicação | Fluxos de dependências e confirmação; regras próprias do usuário, não inferidas dos concorrentes |
| Financeiro | Resumo do período, movimentação, cobrança, conciliação e confirmação de operação | Foco financeiro da CondoConta; dados de carteira da MyCond |
| Morador | Unidade ativa, ação principal, reservas, visitantes, encomendas e comunicados | Jornadas mobile de TownSq/uCondo/MyCond |
| Personalização | Prévia clara/escura, logo, acento, contraste, canais de saída, publicar/reverter | Personalização de cores e módulos divulgada pela MyCond, expandida como proposta CondPulse |

## 7. Uso das referências visuais

Manter os links e créditos do fornecedor ao lado de qualquer imagem usada para estudo. Os kits e imagens são materiais das respectivas empresas; disponibilidade pública não equivale a licença de reutilização comercial. Os arquivos de marca do CondPulse, a comunicação publicitária e o produto final devem conter desenhos próprios. É preferível que o caderno faça comparações de tarefa com pranchas originais e encaminhe às referências oficiais, sem embutir grandes coleções de telas concorrentes.

Nenhuma captura pública permite concluir a stack CSS/framework da CondPulse, nem a stack atual dos fornecedores. A identificação da tecnologia real da CondPulse depende da inspeção do repositório e dos arquivos do projeto.
