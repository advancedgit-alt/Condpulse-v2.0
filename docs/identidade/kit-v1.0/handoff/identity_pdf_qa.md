# QA final — Manual de Identidade CondPulse v1.0

**Resultado: aprovado para entrega documental.** Esta verificação cobre integridade, navegação e diagramação dos arquivos; não constitui implantação do design system no produto nem homologação de equipamentos.

## Arquivos finais

- `CondPulse_Manual_Identidade_Marca_Sistema_v1.0.pdf`: 36 páginas A4 retrato; capa, 8 pranchas visuais, índice clicável e conteúdo integral.
- `CondPulse_Manual_Identidade_Marca_Sistema_v1.0.md`: conteúdo editável, incluindo o manual original e o anexo integral, preservados uma vez e na ordem prevista.
- Script reprodutível: `tmp/visual/build_identity_pdf.py`.

## Integridade do conteúdo

| Verificação | Resultado |
|---|---|
| Manual fonte | 5.518 palavras preservadas |
| Anexo fonte | 3.438 palavras preservadas |
| Markdown consolidado | 8.970 palavras, incluindo título de anexo |
| Unidades de conteúdo procuradas no texto extraído | 806 de 806 localizadas |
| Células de tabelas verificadas independentemente | 596 de 596 presentes |
| URLs externas clicáveis | 32 |
| Links internos do índice | 76, destinos válidos |
| Marcadores de navegação | 45 |
| Fontes | Lato Regular e Bold incorporadas; corpo 10,75 pt |
| Limites de página | Nenhum trecho de texto fora da página |
| Conteúdo de concorrentes | Nenhum no manual principal |
| Alteração dos Markdown originais | Nenhuma |

## Mapa do documento

| Páginas | Conteúdo |
|---|---|
| 1 | Capa |
| 2–9 | Marca positiva/negativa, variantes, proteção, paletas, tipografia, componentes/regras e templates |
| 10–11 | Índice clicável |
| 12–24 | Manual integral: tecnologia atual, marca, sistema e governança |
| 25 | Fontes do manual |
| 26–36 | Anexo A integral: personalização por cliente, permissões, dados, configuração e critérios de aceite |

## Verificação visual

As 36 páginas foram renderizadas com PyMuPDF e inspecionadas em seis folhas de contato. Foram também conferidas em tamanho ampliado as páginas 4, 25, 30, 31, 32 e 35 da versão final para área de proteção, fontes, URLs, tabelas densas, JSON e critérios de aceite. Não foram observados cortes, sobreposições, caracteres faltantes ou espaços reservados não resolvidos.

Evidências: `tmp/visual/identity_pdf/render/page-01.png` a `page-36.png`, e `tmp/visual/identity_pdf/contacts/contact-01.jpg` a `contact-06.jpg`. Registro estruturado: `tmp/visual/identity_pdf/qa_report.json`.

## Decisões editoriais preservadas

- A marca existente foi aplicada a partir do kit fornecido, sem redesenho.
- A regra de proteção x = 25 está identificada como proposta desta versão e declara a diferença em relação ao guia anterior.
- A capa e o rodapé identificam o documento como versão proposta 1.0.
- As pranchas originais antecedem o texto integral e não substituem capítulos.
- Links de fontes permaneceram humanos e clicáveis; sinais de pontuação final foram separados dos endereços.
- Nenhuma publicação do site ou gravação remota foi realizada por este processo.

## Identificação do arquivo

SHA-256 do PDF: `cdd46c3ffcc7a7d1ccb008a2d50cbdd35b8175ddeb66c9dab0a38aa76fca7fd5`.

Tamanho do PDF: 795.217 bytes.
