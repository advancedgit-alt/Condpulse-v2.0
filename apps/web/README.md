# Web residencial CondPulse

React/TypeScript estrito, Vite, React Router e TanStack Query, com package-lock.json.
Implementa /entrar, /contextos e /morador/:tenantId/:unitId/reservas.
Login, seleção de unidade, regras/agenda, criação/cancelamento e saída usam a API real
da própria origem. Sem dados demo no aplicativo ou tokens em localStorage.

```sh
npm ci --no-fund --no-audit
npm test
npm run build
npx playwright install chromium
npm run test:e2e
npm run dev
```

Servidor de desenvolvimento:127.0.0.1:4173, com proxy privado /auth,/v1 para127.0.0.1:8080.
Testes de navegador usam fixtures sintéticas exclusivamente no runner.
O CI também testa o build de produção no Nginx com CSP e filesystem somente leitura.
Rotas de portaria/gestão, outros módulos do morador e aplicativo Expo permanecem pendentes.
Configuração, limites e gates de publicação em ../../docs/WEB_IMPLANTACAO.md.
