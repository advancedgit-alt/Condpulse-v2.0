# Web residencial CondPulse

React/TypeScript estrito, Vite, React Router e TanStack Query, com versões diretas fixadas
e package-lock.json. A identidade visual usa os tokens CondPulse e Lato distribuída
localmente sob OFL-1.1, sem CDN ou chamadas de fonte no navegador.

## Protocolo e segurança

- A web chama somente /auth e /v1 na própria origem, com cookie e no-store.
- Nenhum token Auth é colocado em localStorage, sessionStorage ou URL. CSRF permanece
  em memória e é consultado novamente depois de login/refresh, quando o servidor o rotaciona.
- Somente401 SESSION_REFRESH_REQUIRED permite uma renovação e uma repetição, preservando
  corpo/chave de reserva. Falha de rede/5xx/abort não gera repetição automática do comando.
- Resultado desconhecido preserva a intenção e a chave; a tela exige retry explícito ou
  reconsulta, sem anunciar sucesso. Outra intenção recebe chave diferente.
- Conta/tenant/unidade fazem parte do cache. Troca de contexto cancela consultas e remove
  conteúdo anterior. Negação de acesso e logout ocultam os dados e as ações.
- Reservas são da unidade. O contrato atual não expõe autoria nem canCancel; a confirmação
  informa que somente o autor pode cancelar e o servidor decide. Papéis exibidos não
  concedem poderes ao cliente.
- Datas digitadas são interpretadas no fuso do condomínio. Janelas ambíguas/inexistentes
  não são normalizadas silenciosamente pelo fuso do navegador.

## Desenvolvimento e verificação

No diretório apps/web:

```sh
npm ci --no-fund --no-audit
npm test
npm run build
npx playwright install chromium
npm run test:e2e
npm run dev
```

O servidor local fica em127.0.0.1:4173 e encaminha /auth e /v1 para127.0.0.1:8080.
Isso permite trabalhar a interface; Auth real exige origem HTTPS e configuração do BFF.
Fixtures de login/reservas existem apenas no runner de testes. O aplicativo distribuído
não possui modo demo nem fallback de sucesso quando a API está indisponível.

## Publicação na mesma origem

O pacote operacional está em docs/DEPLOY_COOLIFY.md e infra/compose.app.yaml;
o job de migração fica em outro arquivo, sem credenciais DDL na API.

1. Provisionar o ambiente CondPulse isolado, host/domínio HTTPS e cofre antes de publicar.
2. Atestar o bootstrap do banco, executar baseline002 oficial e migrarV003 pelo Flyway,
   usando identidade DDL separada. Provisionar login runtime apenas no grupo cp_api.
3. Configurar a API conforme infra/CONFIGURACAO.md. CP_BFF_ORIGIN é exatamente a origem
   HTTPS da web; não há CORS ou URL de backend escolhida pelo navegador.
4. Construir infra/Dockerfile.web na raiz e publicar imagem por digest. Imagens base web
   estão fixadas por digest; o build usa npm ci e executa typecheck antes de gerar assets.
5. Executar web e API em rede privada exclusiva, com nome DNS api e porta interna8080.
   O Nginx encaminha /auth e /v1; as demais rotas recebem o aplicativo React.
6. Expor somente a web por TLS no proxy do host. Não publicar porta da API, banco ou
   Docker socket. Usar UID101, filesystem somente leitura, tmpfs /tmp, cap-drop ALL e
   no-new-privileges no container web; o CI testa essa configuração.
7. Configurar readiness da API em /actuator/health/readiness na rede privada. /healthz
   da web verifica somente o servidor de assets; não comprova saúde do banco ou login.
8. Homologar Auth real, TLS/pooler, CSRF, criação/cancelamento, revogação de vínculo,
   limitação de tentativas, logs sem credenciais e navegação móvel antes de habilitar
   CP_BFF_ENABLED e CP_RESERVATIONS_ENABLED. Configuração e migração não são feitas pela web.

Não se registra hospedagem adicional nem se compartilha recurso/segredo da Advanced.
Host pago, domínio e custos precisam do aceite definido em CLAUDE.md. Esta documentação
e os arquivos de imagem não representam um deploy já realizado.
