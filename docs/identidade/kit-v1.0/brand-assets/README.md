# CondPulse — kit de marca derivado do original

**Status: proposta para aprovação visual.** Data: 31/10/2026. Nenhum arquivo foi implantado no sistema.

## Origem e integridade

Original do repositório [advancedgit-alt/Condpulse-v2.0](https://github.com/advancedgit-alt/Condpulse-v2.0), branch consultada ccr-5e725112-3x8hyg, commit **7130b52769ad2acaac420fd2896b32b1ad7484ad**:

- [Horizontal SVG](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/marca/condpulse_horizontal.svg).
- [App icon SVG](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/marca/condpulse_app_icon.svg).
- [Guia visual inicial](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/GUIA_MARCA.md).

O guia do repositório descreve os originais como reconstrução técnica de um conceito e proposta de identidade. Este kit não muda esse status, não constitui pesquisa de marca/INPI e não declara reprodução exata do conceito raster original.

A geometria do original foi preservada: os mesmos paths (d), transforms, stroke-width e stroke-linecap. O lettering já está em traçados; **não depende de fontes instaladas**. As variantes alteram apenas cor e metadados. O símbolo isolado reutiliza os dois primeiros paths do horizontal. O app icon preserva placa, arredondamento, grupo e transformação originais; a placa muda de branco para navy nas versões negativas.

## Conteúdo

| Pasta/arquivo | Conteúdo |
| --- | --- |
| svg/ | 12 SVG: 4 horizontais + 4 símbolos + 4 app icons |
| png/ | 24 PNG: as 12 variantes em 2× e 4× |
| favicons/ | PNG 32×32, 180×180, 512×512 nas opções clara/escura e favicon.ico 32×32 |
| reference/ | Cópias dos dois SVG originais |
| condpulse-prancha-variantes.png | Comparação visual das 12 versões |
| manifest.json | Fonte, commit, integridade da geometria, contrastes calculados e SHA-256 dos assets |

### Nomes e uso

| Sufixo | Marca | Fundo recomendado |
| --- | --- | --- |
| positiva | navy #102A43 + teal #007F86 | branco ou superfície clara com contraste verificado |
| negativa | branco #FFFFFF + teal claro #5DD6C7 | navy #102A43 |
| mono-branca | branco | navy, teal escuro ou imagem com placa de contraste adequada |
| mono-navy | navy #102A43 | branco ou claro |

Os nomes completos começam por condpulse-horizontal-, condpulse-simbolo- ou condpulse-app-icon-. Nos PNG, @2x e @4x indicam escala em relação ao tamanho lógico de referência.

### Resolução e transparência

| Família | Referência SVG | PNG 2× | PNG 4× |
| --- | --- | --- | --- |
| Horizontal | 724×210 | 1448×420 | 2896×840 |
| Símbolo isolado | 172×198 | 344×396 | 688×792 |
| App icon | 512×512, viewBox 220×220 | 1024×1024 | 2048×2048 |

Horizontal e símbolo têm fundo transparente. App icon mantém a placa arredondada branca/navy do original e transparência fora dela. Não confundir essa composição com arquivo final de loja: a distribuição deve validar máscaras, áreas seguras e transparência exigidas por cada plataforma. Não foi produzida uma build iOS/Android.

Favicons são derivados do app icon existente. O arquivo 180 px pode servir de referência para touch icon; 512 px para manifesto PWA, sujeitos ao teste na aplicação. A exportação de 32 px e o ICO foram verificadas em dimensão, mas precisam ser avaliados no navegador/tela alvo. Não usar o logo horizontal dentro de um favicon.

## Área de proteção

Proposta de padronização para este kit:

- **1x = 25 unidades do desenho original do símbolo**, igual à espessura nominal do traço externo do C.
- Reservar **no mínimo 1x em todos os lados da área visual da marca**.
- Aplicar a margem externamente; não esticar ou reorganizar os paths.
- O canvas existente não garante por si só essa margem em todos os lados. Usar um contêiner que preserve a proteção.
- Quando o horizontal for exibido com largura W, 1x = W × 25/724. Exemplo: largura 160 px → aproximadamente 5,5 px por lado, podendo arredondar para cima.
- A regra de 1x é uma proposta de evolução. O guia inicial do repositório mencionava 1/2 da altura do símbolo; a equipe deve aprovar uma única regra antes da publicação do manual definitivo. Não tratar as duas como regras simultâneas.

## Reduções propostas a testar

| Uso | Mínimo proposto |
| --- | --- |
| Horizontal em produto/mídia digital | 160 px de largura |
| Símbolo isolado | 32 px de altura |
| Horizontal impresso | 30 mm de largura |
| Símbolo impresso | 8 mm de altura |
| Favicon | Arquivo específico 32 px; avaliar legibilidade no contexto |

Esses valores são hipóteses de design, não medições de homologação. Para espaços menores, usar o símbolo e nome textual acessível no componente. Testar em monitor comum, tela de alta densidade, zoom, impressão e materiais reais. Não reduzir até o lettering perder legibilidade.

## Contraste calculado

Cálculo sRGB de luminância relativa e razão (Lmais clara + 0,05)/(Lmais escura + 0,05), aplicado a pares de cores sólidos:

| Frente | Fundo | Razão |
| --- | --- | --- |
| Navy #102A43 | Branco #FFFFFF | 14,64:1 |
| Teal #007F86 | Branco #FFFFFF | 4,79:1 |
| Branco #FFFFFF | Navy #102A43 | 14,64:1 |
| Teal claro #5DD6C7 | Navy #102A43 | 8,30:1 |
| Teal claro #5DD6C7 | Branco #FFFFFF | 1,76:1 |

A negativa com teal claro foi desenhada para navy; não usar esse teal claro como texto sobre branco. A razão matemática não equivale a auditoria completa de acessibilidade: transparência, imagem/fundo, antialiasing, tamanho e contexto podem mudar a leitura. O lettering de logo tem tratamento normativo diferente do texto de interface; as cores usadas em botões, rótulos e estados devem ser validadas por sua função no produto.

## Regras de aplicação

1. Preservar proporção, paths, espaçamento entre símbolo e lettering, traço e composição.
2. Escolher a variante de acordo com o fundo; usar placa sólida quando o fundo não oferecer leitura estável.
3. Evitar deformação, rotação decorativa, contorno extra, sombra, gradiente e recoloração arbitrária.
4. Usar o SVG original/variante diretamente em componentes; não reconstruir o logo com CSS.
5. Logos fornecidos por clientes white label são assets distintos. Não recolorir a assinatura CondPulse com a cor do cliente sem regra formal aprovada.
6. Manter cores de erro/atenção/sucesso independentes da cor institucional do cliente.
7. Incluir texto alternativo CondPulse quando a marca identifica o produto; se já houver nome equivalente adjacente, evitar duplicação para leitor de tela.
8. Conferir legibilidade em contexto antes de publicação, sobretudo favicons, modo escuro e aplicação sobre fotografia.

## Verificação executada

- Igualdade de geometria dos 12 SVG contra os paths originais.
- Renderização por Inkscape dos 24 PNG e 6 PNG de favicon.
- Conferência automatizada das dimensões de todos os PNG.
- Conferência visual da prancha de variantes.
- Cálculo de contraste dos pares documentados.

Não executado: teste em aplicação publicada, navegador real por plataforma, loja móvel, produção gráfica ou aprovação final da marca.

