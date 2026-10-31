# CondPulse — referências oficiais para o design do produto

Pesquisa em 31/10/2026. Material de apoio ao manual e ao protótipo; não comprova funcionalidades já implantadas. A auditoria interna do projeto, comunicada pelo agente principal, identificou React/TypeScript/Vite, CSS próprio, Lato 400/700, SVGs próprios e a base cromática #102A43, #007F86 e #F4F7FA. Portanto, as referências abaixo orientam decisões de interface; não justificam trocar a biblioteca ou a fonte automaticamente.

## 1. Cinco referências centrais

### 1.1 Linear — estrutura de aplicativo e hierarquia

Fonte oficial: https://linear.app/now/how-we-redesigned-the-linear-ui

O artigo de 28/03/2024 apresenta telas de navegação lateral, cabeçalhos, painéis, modos claro/escuro e estudos de alinhamento. O relato informa redução de ruído visual e ajuste de hierarquia e densidade; descreve uma base comum de variáveis para os temas. É uma referência de processo e de telas da época do artigo, não uma auditoria de todas as telas atuais.

**Aplicação proposta ao CondPulse:** menu lateral persistente; cabeçalho com condomínio e perfil ativos; título, filtros e ações em ordem consistente; painel de detalhes sem perder a lista; fundo neutro com cor de marca concentrada em seleção e ação principal. Testar a mesma estrutura em Pessoas, Reservas, Ocorrências e Acessos antes de aplicá-la a todo o produto. Não copiar a marca, as telas ou a densidade de um produto de engenharia sem avaliar os operadores de portaria.

### 1.2 Stripe Dashboard — visão operacional e contexto de conta

Fonte oficial: https://docs.stripe.com/dashboard/basics

A documentação descreve navegação por recursos, busca, atalhos, filtros, exportação e visão inicial com gráficos e notificações relevantes. Também apresenta operações em contexto de conta e de organização.

**Aplicação proposta ao CondPulse:** a primeira dobra deve responder “o que requer atenção agora?”. Mostrar poucas métricas relevantes ao perfil, fila de pendências e contexto de condomínio permanente. O MASTER pode ter visão agregada; o gestor local só vê o escopo autorizado. Sempre explicitar o período e a última atualização de um indicador. Um número pode abrir sua lista filtrada. Não fazer da home um mural de cartões decorativos nem uma vitrine comercial.

### 1.3 Shopify Polaris — tarefas administrativas e adaptação móvel

Fontes oficiais:

- Componentes atuais: https://shopify.dev/docs/api/app-home/latest/web-components
- Tabela responsiva: https://shopify.dev/docs/api/app-home/latest/web-components/layout-and-structure/table
- Padrões de páginas: https://shopify.dev/docs/api/app-home/latest/patterns

O catálogo documenta formulários, estados vazios, badges, banners, tabelas e composição de páginas administrativas. A tabela oficial adapta a apresentação de linhas/colunas para lista em telas menores. O antigo Polaris React exibe aviso de descontinuação: https://polaris-site-prod-kit.shopify.prod.shopifyapps.com/getting-started/polaris-101

**Aplicação proposta ao CondPulse:** cadastro de morador em seções curtas, erros próximos aos campos, ação principal estável e estado de alterações não salvas. No celular, cada pessoa ou ocorrência vira um item com identidade, unidade, estado e ação; detalhes secundários abrem na sequência. Estudar o padrão, mantendo os componentes próprios do CondPulse. Polaris atual é voltado ao ecossistema Shopify e não é uma indicação de dependência para este projeto.

### 1.4 Atlassian Design System — tokens e temas sem fragmentar o produto

Fontes oficiais:

- Explicação dos tokens: https://atlassian.design/tokens/design-tokens/
- Temas em código: https://atlassian.design/foundations/tokens/use-tokens-in-code/

Atlassian define tokens como pares nome/valor que registram decisões repetidas de design. Temas mudam os valores mantendo os mesmos nomes semânticos. A orientação é escolher tokens pelo significado, não apenas pela semelhança visual da cor.

**Aplicação proposta ao CondPulse:** o componente usa `text.primary`, `action.primary`, `status.danger`, `surface.panel`; não conhece a cor HEX de cada cliente. A marca ajusta apenas um conjunto permitido. Erro continua sendo erro, independentemente da cor institucional. Modo escuro recebe valores próprios validados. Referência conceitual; não há necessidade de instalar Atlaskit.

### 1.5 IBM Carbon — tabelas densas e operações verificáveis

Fonte oficial atual: https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines

A documentação apresenta anatomia de tabela, barra de busca/filtros/ações, ordenação, paginação, expansão de linhas e ações em lote. Existem demonstrações interativas e alternativas de altura de linha.

**Aplicação proposta ao CondPulse:** Pessoas, Visitantes, Eventos de acesso e Auditoria precisam de tabela legível, filtros visíveis, ordenação identificável e ações por linha. Só oferecer operação em lote quando fizer sentido para a regra de negócio. Expansão ou painel lateral preservam o contexto. Não expor ações perigosas de abertura física como operação em lote. Densidade compacta é opcional no desktop; a portaria deve privilegiar alvos maiores e reconhecimento rápido.

## 2. Complemento para reservas e agenda

Ant Design Calendar: https://ant.design/components/calendar/

O componente oficial demonstra visões de mês/ano e apresentação de eventos. Serve para estudar hierarquia de data, seleção e conteúdo por dia. **Proposta CondPulse:** disponibilizar mês, semana e lista, com lista como alternativa móvel e acessível; mostrar horário, área, solicitante autorizado e estado da reserva. Conflitos e indisponibilidade precisam de texto/ícone e explicação. Uma agenda é visualização; disponibilidade e permissão dependem das regras no servidor. Não se recomenda adicionar Ant Design apenas para reproduzir o exemplo.

## 3. Como combinar as referências

| Necessidade CondPulse | Direção proposta | Referência de estudo |
|---|---|---|
| Estrutura global | Navegação lateral, contexto ativo e painel de detalhes | Linear |
| Home MASTER | Saúde operacional, condomínios com pendência, incidentes e última atualização | Stripe + componentes próprios |
| Home gestor | Pendências acionáveis, reservas do dia e comunicados | Stripe + Polaris |
| Portaria | Busca de pessoa, evento atual, local físico e resultado textual; poucos caminhos | Carbon + princípios de hierarquia |
| Cadastros | Formulários em seções, validação local, alterações não salvas | Polaris |
| Pessoas e auditoria | Tabela, filtros salvos autorizados, paginação e detalhe contextual | Carbon |
| Reservas | Agenda + lista; estados e conflitos explícitos | Ant Design como referência complementar |
| White-label | Tokens semânticos, valores por tema, catálogo de componentes compartilhado | Atlassian |
| Celular | Lista orientada à tarefa; prioridade de campos e ações | Polaris |

O resultado deve ter linguagem própria do CondPulse. A referência é o comportamento útil da interface; screenshots de terceiros devem ser identificados como referência e não usados como material promocional da marca.

## 4. Tipografia e ícones

### Lato como padrão atual

Manter Lato 400 e 700 no ciclo inicial preserva coerência com o sistema auditado. As medidas de hierarquia, espaçamento, contraste e estados têm prioridade sobre uma mudança de fonte. Se forem necessários pesos adicionais, incorporar arquivos reais desses pesos: evitar depender de negrito artificial. Proposta: corpo 14–16 px, rótulos 14 px, texto auxiliar 12–14 px conforme contexto, títulos de seção 18–20 px e títulos de página 24–28 px. Essa escala é uma decisão de projeto a validar nas telas reais, não uma exigência da licença ou do WCAG.

Lato é distribuída sob SIL Open Font License 1.1. O repositório oficial declara “Lato” como Reserved Font Name. Preservar aviso de copyright e licença na distribuição dos arquivos; observar as regras aplicáveis se a fonte for modificada. Fontes: https://www.latofonts.com/download/ e https://github.com/latofonts/lato-source/blob/master/LICENSE.txt

### Inter como alternativa futura

Inter oferece pesos variáveis, desenhos ópticos para texto/display e recursos como números tabulares. Sua licença é SIL OFL 1.1. Fonte: https://rsms.me/inter/ e licença: https://raw.githubusercontent.com/rsms/inter/master/LICENSE.txt

Não migrar agora por preferência estética. Se avaliada, medir legibilidade em listas, ocupação de rótulos, carregamento e estabilidade de layout, acentuação em português, desktop e celular. Manter uma fonte de interface por versão publicada da marca. A família escolhida vem de catálogo aprovado e auto-hospedado; o cliente não insere URL de fonte arbitrária.

### Iconografia

Consolidar os SVGs próprios existentes em tamanhos, alinhamento e espessura consistentes antes de trocar a família. Lucide é uma opção futura com catálogo documentado e licença ISC; alguns ícones herdados de Feather mantêm aviso MIT. Preservar os avisos correspondentes. Fonte: https://lucide.dev/license

Proposta: ícones de 20/24 px; o alvo de clique pode ser maior que o desenho. Ações críticas têm verbo visível. Ícone decorativo não duplica leitura pelo leitor de tela. Não misturar famílias com pesos incompatíveis nem usar emoji como ícone de operação.

## 5. Acessibilidade: requisito verificável e decisão de projeto

Meta proposta: WCAG 2.2 nível AA para os fluxos implementados. Uma paleta ou um protótipo isolado não comprova conformidade do sistema completo.

| Tema | Referência normativa | Aplicação no CondPulse |
|---|---|---|
| Texto | 1.4.3 AA: 4,5:1; texto grande 3:1, com exceções definidas | Validar cada par texto/fundo de cada tema. Texto grande conforme definição WCAG, não qualquer título pequeno |
| Componentes e gráficos | 1.4.11 AA: informação visual necessária para reconhecer controles/estados e gráficos significativos, 3:1 contra adjacência | Validar bordas essenciais, estados, foco e gráficos; não exigir 3:1 de todo divisor decorativo |
| Cor | 1.4.1 A: cor não é o único canal | “Autorizado”, “Bloqueado”, “Pendente”, “Sem comunicação” com texto e ícone |
| Foco | 2.4.7 AA exige indicador visível; 2.4.11 AA evita que fique inteiramente oculto | Teclado completo, foco persistente e proteção contra cabeçalho/rodapé fixo encobrindo ação |
| Aparência do foco | 2.4.13 é AAA: área equivalente a perímetro de 2 CSS px e contraste 3:1 entre estados, com exceções | Adotar indicador robusto como escolha de projeto, sem rotulá-lo como exigência AA |
| Alvos | 2.5.8 AA: 24×24 CSS px, com exceções de espaçamento e outras | Preferir 44×44 nos fluxos por toque e portaria; 44×44 é referência do critério AAA 2.5.5, não a dimensão mínima AA |
| Refluxo | 1.4.10 AA contempla 320 CSS px de largura para conteúdo com rolagem vertical; exceções para conteúdo que precisa de duas dimensões | Formulários e navegação adaptam; tabela complexa pode ocupar região horizontal própria, sem impor rolagem dupla a toda a página |

Também testar teclado, nomes acessíveis, mensagens de erro, leitura por tecnologia assistiva, zoom e preferência de movimento reduzido. O editor white-label precisa rejeitar combinações inadequadas antes da publicação e explicar o par que falhou.

Fontes W3C:

- Norma: https://www.w3.org/TR/WCAG22/
- Contraste: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
- Contraste não textual: https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html
- Cor: https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
- Foco visível: https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html
- Foco não oculto: https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html
- Aparência do foco: https://www.w3.org/WAI/WCAG22/Understanding/focus-appearance.html
- Alvo mínimo: https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum
- Alvo ampliado: https://www.w3.org/WAI/WCAG22/Understanding/target-size-enhanced.html
- Refluxo: https://www.w3.org/WAI/WCAG22/Understanding/reflow.html

## 6. Direção de protótipos

Produzir dados demonstrativos identificados como fictícios. Demonstrar no mínimo: home do gestor; portaria; tabela de pessoas; detalhe de pessoa; reservas; editor de identidade com prévia; tela móvel; modo escuro. A mesma base visual deve suportar CondPulse e uma marca fictícia de condomínio sem alterar a estrutura.

Na portaria, mostrar resultado e confiabilidade: “Solicitação enviada”, “Abertura confirmada”, “Sem confirmação do dispositivo” são estados distintos. Nenhuma troca de cor pode representar confirmação que o sistema não recebeu. O tema também não altera regras de idade, visitante, área ou dispositivo.

