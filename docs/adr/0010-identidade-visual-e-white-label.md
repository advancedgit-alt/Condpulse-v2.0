# ADR-0010 - Identidade visual canônica, tokens semânticos e white label

Estado: proposta para aceite do responsável. O kit "CondPulse — Identidade, sistema e white label v1.0"
(31/10/2026) foi entregue pelo responsável e está em `docs/identidade/kit-v1.0/`; a aprovação visual final da
marca canônica continua pendente. Número: a ADR-0009 está reservada pela trilha facial (gateway do
condomínio, citada em `docs/integracoes/facial/AUDITORIA_AC001.md`), por isso esta decisão usa 0010.

## Contexto

A auditoria do kit (`research/frontend_audit.md`, commit `7130b52`) encontrou três marcas diferentes (três
barras em CSS na web, "C envolvendo três edifícios" em `spec/design/marca`, ícone "A" de template no mobile),
paletas divergentes entre web e mobile, CSS pedindo pesos 550/650/750/800 com apenas Lato 400/700
carregada, mais de cem cores fixas espalhadas nas folhas, nenhum tema escuro e nenhum ponto de extensão de
marca por condomínio. Os JSON de tokens existentes (`packages/design-tokens`, `spec/design`) não são
consumidos pelo código.

## Decisão

1. **Marca canônica:** o símbolo "C envolvendo três edifícios" e suas variantes do kit (positiva, negativa,
   mono branca, mono navy; horizontal, símbolo, app icon). Web e mobile passam a usar esses ativos.
2. **Paleta e contraste:** navy `#102A43`, teal `#007F86`, fundo `#F4F7FA`, superfície `#FFFFFF`, texto
   secundário `#52667A`, link `#00666D`, erro `#B42318`, acento escuro `#5DD6C7`, e as cores de estado do
   manual. Pares obrigatórios atendem WCAG 2.2 AA conforme `handoff/contrast-report.md`; isso não é
   certificação da aplicação.
3. **Tipografia:** Lato 400 (corpo) e 700 (títulos, ações e destaques). Nenhum outro peso sem arquivo real.
4. **Fonte de verdade:** tokens semânticos `--cp-*` derivados de `handoff/tokens.css`. Componentes consomem
   significado; HEX fixo fora da camada de tokens é exceção documentada. Tema claro e escuro com matrizes
   próprias, `prefers-color-scheme` por padrão e preferência manual do usuário salva no navegador.
5. **Direção visual:** "A. Operacional CondPulse" do caderno de referências.
6. **Stack:** CSS próprio continua; nenhuma biblioteca visual nova (Tailwind, kits de componentes) é adotada
   por esta ADR.
7. **White label em fases:** primeiro um ponto de extensão no frontend (override de acento e logo por
   condomínio a partir de um objeto de configuração validado contra `tenant-branding.schema.json`, com
   fallback automático para o padrão quando o contraste mínimo falhar). Persistência, upload, publicação e
   restauração ficam para depois da trilha facial, pois exigem migration; o desenho segue o Anexo A do manual.
   Cores de estado e foco nunca são sobrescritas pela marca do cliente; tema não concede permissão.

## Consequências

- Entrega em PRs pequenos: documentação e ativos; tokens, temas e marca na web; ícone, splash e cores no
  mobile. Sem mudança de rota, regra de negócio ou backend.
- A CSP da web precisa admitir o manifesto (`manifest-src 'self'`) para servir favicons e manifest da marca.
- `packages/design-tokens/tokens.json` e `spec/design/tokens.json` ficam como referência histórica até uma
  geração automática única (web e native) ser decidida.
- Pendências: aprovação final da marca canônica e da regra de área de proteção; validação do tema escuro em
  ambiente real de portaria; teste de ícone e splash em build iOS/Android; backend de white label.
