# Atualização em tempo real da unidade (CP-036)

Flag: `CP_REALTIME_ENABLED` (API, padrão `false`). Não ligada na Vercel. Sem migration: o canal só lê tabelas que já
existem, como `cp_api`, pelas mesmas políticas de leitura.

## Como funciona

- **Rota**: `GET /v1/tenants/{tenantId}/units/{unitId}/events` (Server-Sent Events). Só leitura, sem CSRF. Web pelo
  cookie do BFF (`EventSource` na mesma origem); celular pelo token Bearer.
- **Abertura validada**: antes de abrir o canal a API repete a validação do condomínio (conta ativa, condomínio ativo,
  vínculo residencial vigente na unidade ativa). Recusa volta como problema JSON com o status certo (401, 404, 429, 503),
  mesmo com `Accept: text/event-stream`; nunca como canal vazio.
- **Eventos**:
  - `ready`: assuntos acompanhados e intervalo de reconexão (`retry`);
  - `changed`: `{"topics":[...]}`, só nomes de assunto (`reservations`, `parcels`, `arrivals`, `notifications`,
    `announcements`, `requests`);
  - `ping`: nada mudou desde a passada anterior;
  - `end`: `{"reason": "revoked" | "expired" | "timeout" | "unavailable" | "shutdown"}`.
- **Sem dado pessoal**: o evento não leva identificador, nome, texto nem situação de registro. A tela consulta o detalhe
  pelas rotas normais, com as permissões de quem abre.
- **Estado resumido**: para cada assunto, quantidade, soma das versões e última alteração das linhas que o leitor pode
  ver (uma instrução, um instantâneo). Em pedidos, também as mensagens visíveis à unidade (resposta nova não muda a
  versão do pedido; comentário interno não conta). Mudança que o leitor não pode ver (outra unidade, outro condomínio) não altera
  nada (`RealtimeJdbcTest`).
- **Revogação fecha o canal**: a cada passada (padrão 5 s), por canal, primeiro a credencial e depois o estado:
  - web: a sessão BFF é conferida no banco; sair ou ter a sessão removida encerra com `end: revoked`;
  - celular: o canal fecha quando o token Bearer expira (`end: expired`);
  - vínculo revogado, conta suspensa, unidade ou condomínio inativos: `end: revoked`, sem enviar o estado.
- **Reconexão consulta o estado**: o canal vive no máximo `CP_REALTIME_LIFETIME_SECONDS` (padrão 300 s) e termina com
  `end: timeout`; o navegador reconecta e o `ready` seguinte faz a tela consultar tudo de novo. Eventos perdidos durante
  uma queda não importam.
- **Limites**: até `CP_REALTIME_MAX_STREAMS` canais por instância (padrão 40) e `CP_REALTIME_MAX_PER_ACCOUNT` por conta
  (padrão 3); acima disso, 429 `REALTIME_LIMIT_REACHED` sem derrubar canais abertos. Uma única linha de execução percorre
  os canais; cada consulta usa uma transação curta, então o pool de 2 conexões nunca fica preso a um canal aberto.
- **Desligamento**: a instância avisa cada canal (`end: shutdown`) antes de parar.

## Web

`UnitEvents` fica montado só nas telas da unidade com vínculo residencial. `changed` invalida só as consultas do assunto
(consulta ainda na primeira carga é feita de novo quando termina: a resposta em andamento pode ser anterior à mudança —
`invalidateFresh`, na web e no celular);
toda abertura do canal (inclusive a primeira, que pode começar depois de uma mudança) invalida todas as da unidade; `revoked` confere a identidade de novo e não reconecta; `expired` faz a consulta
seguinte renovar a sessão e reabre em 5 s. Recusa do servidor (função desligada, 401, 429, 503) não mostra erro: nova
tentativa com espera de 30 s, dobrando até 5 min. Sem o canal, a tela funciona como antes (consulta ao abrir e botões de
atualizar).

## Evidências locais e de CI

- `RealtimeHubTest`: só nomes de assunto; credencial conferida antes do estado; motivos de encerramento; prazo; limites;
  cliente que saiu; desligamento.
- `RealtimeSecurityIntegrationTest`: cadeia HTTP real com cookie e Bearer; sem sessão 401; unidade negada 404 em JSON;
  limite 429; sessão removida fecha o canal na passada seguinte; rota ausente com a flag desligada.
- `RealtimeJdbcTest` (PostgreSQL descartável, login da API): todos os assuntos lidos; encomenda de outra unidade não muda
  nada; resposta visível da gestão muda `requests` e comentário interno não; outra unidade, outro condomínio, equipe sem unidade, vínculo revogado e conta suspensa recusados.
- `tests/runtime/realtime-flag-smoke.sh`: jar real com a flag ligada; canal sem sessão ou com Bearer inválido responde
  401 sem abrir stream.
- Web: `src/api/events.test.ts` (Vitest) e `e2e/realtime.spec.ts` (Playwright): aviso novo aparece sozinho, reconexão
  consulta de novo, revogado não reconecta, função desligada não insiste nem mostra erro, canal não abre fora da unidade.

## Pendências

- **Celular** (PR da parte móvel): canal lido por `XMLHttpRequest` em fluxo com o token Bearer no cabeçalho, mesmas regras
  da web; fecha em segundo plano e consulta tudo ao voltar. Ver `apps/mobile/README.md`. Ensaio em aparelho real depende
  do build de desenvolvimento (bloqueio registrado em STATUS).
- **Vercel**: confirmar que o Container Runtime (beta) entrega a resposta em fluxo, sem buffer, e o tempo máximo de
  requisição, antes de ligar a flag; ajustar `CP_REALTIME_LIFETIME_SECONDS` abaixo desse limite. Na imagem nginx, o
  cabeçalho `X-Accel-Buffering: no` desliga o buffer.
- **Capacidade**: os limites são hipótese inicial, sem teste de carga (CP-052). Com várias instâncias, cada uma tem os
  próprios limites.
- **Ensaio real**: abrir o canal com login real na homologação e observar `changed` depois de uma ação da portaria
  depende do TOTP do titular (bloqueio registrado em STATUS).
