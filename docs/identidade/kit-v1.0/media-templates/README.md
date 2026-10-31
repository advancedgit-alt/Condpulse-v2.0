# CondPulse — templates de comunicação

Quatro **amostras editáveis** de peças originais da marca, com SVG vetorial e PNG no tamanho exato. As composições usam a identidade CondPulse, cartões geométricos e linhas arquitetônicas próprias. Não contêm fotografias, capturas de concorrentes, endereço fictício ou nome de contato inventado.

## Arquivos

| Uso | Dimensão | Fonte editável | Imagem pronta para revisão |
|---|---|---|---|
| Feed vertical | 1080 × 1350 px | `condpulse-feed-1080x1350.svg` | `condpulse-feed-1080x1350.png` |
| Story vertical | 1080 × 1920 px | `condpulse-story-1080x1920.svg` | `condpulse-story-1080x1920.png` |
| Anúncio horizontal | 1200 × 628 px | `condpulse-anuncio-1200x628.svg` | `condpulse-anuncio-1200x628.png` |
| Assinatura de e-mail | 600 × 180 px | `condpulse-assinatura-email-600x180.svg` | `condpulse-assinatura-email-600x180.png` |

## Identidade preservada

- Navy: `#102A43`.
- Teal: `#007F86`.
- Branco: `#FFFFFF`.
- Tipografia: Lato Regular e Lato Bold, a partir dos arquivos fornecidos em `../fonts/`.
- Logo: os caminhos vetoriais das versões positiva e monocromática branca do kit `../brand-assets/svg/` foram copiados sem redesenho, mudança de proporção ou alteração de cor. Não houve reconstrução manual do símbolo ou do nome.

Os SVGs têm textos editáveis e os arquivos TTF incorporados. Alguns editores não utilizam fontes incorporadas em SVG; para editar com fidelidade, instalar Lato Regular e Lato Bold fornecidas no pacote. A licença acompanha a fonte em `../fonts/OFL.txt`.

## Conteúdo demonstrativo

**Chamada principal:** “A rotina do seu condomínio, organizada.”

**Texto de apoio:** “Pessoas, reservas e comunicação em um só lugar.”

**CTA:** “Conheça o CondPulse”.

As quebras de linha variam por formato. Os cartões Pessoas, Reservas e Comunicação são ilustrações vetoriais conceituais, não capturas da aplicação nem comprovação do estado de implementação de funcionalidades. Ajustar a comunicação à versão comercial efetivamente oferecida antes de publicar.

O CTA não aponta para um endereço porque nenhuma URL de campanha foi definida. Na assinatura de e-mail, usar o PNG como imagem e acrescentar o link real no cliente de e-mail quando houver destino aprovado. Recomenda-se manter também assinatura textual e descrição alternativa, pois uma imagem pode não ser carregada pelo destinatário.

## Como adaptar

1. Abrir o SVG no editor vetorial de preferência com Lato instalada.
2. Editar os grupos e textos identificados, preservando o grupo `logo-condpulse` e sua proporção.
3. Ajustar a cópia dentro das margens existentes. Evitar reduzir a letra para encaixar uma mensagem longa; reescrever ou reorganizar as linhas.
4. Exportar no tamanho indicado para cada formato. Para impressão, preparar um arquivo específico com o fornecedor; estes PNGs foram montados para meios digitais.
5. Conferir o preview final no canal de uso. As margens do story são escolhas de projeto para reservar espaço visual; não representam especificação oficial de uma plataforma.

## Verificação realizada

Os quatro PNGs foram renderizados e inspecionados visualmente: texto completo, acentuação, margens, hierarquia, ausência de sobreposição, legibilidade do logo e dimensões. Os caminhos dos logos incorporados foram comparados com as fontes do kit. Nenhuma peça foi publicada ou enviada a terceiros.

`manifest.json` registra dimensões, fontes, paleta, origem e hash dos logos. `generate_media.py` permite reproduzir os arquivos com Python, fontTools, PyMuPDF e os arquivos de marca/fontes do pacote. Para renderizar o PNG com precisão tipográfica, o script transforma os textos em contornos apenas em uma cópia temporária; o SVG entregue permanece editável.
