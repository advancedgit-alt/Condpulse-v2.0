# Carga sintética (CP-052)

`tests/load/api-load.mjs`, executado pelo workflow **CondPulse synthetic load** (`.github/workflows/load-test.yml`):

- Sobe a API real (Spring Security, JWT, JDBC, RLS) contra PostgreSQL 17 **descartável** do runner, com JWKS sintético
  local; nenhum acesso a Supabase, Vercel, segredo ou dado real.
- Semeia 1 condomínio, `sessões/4` unidades com 4 moradores cada e 5 áreas; cada sessão é uma conta com token próprio.
- Carga em malha aberta (taxa fixa) com mistura: 70% leituras (`/v1/me`, unidades, áreas, reservas da unidade),
  20% disponibilidade e 10% gravações (criar reserva com `Idempotency-Key`; parte das criadas é cancelada).
- Relatório: p50/p95/p99/máx por classe e total, 2xx, 4xx de regra (conflito, cota), erros (5xx, 401/403/404,
  rede, tempo), taxa obtida, pedidos descartados pelo limite de 256 em voo, conexões máximas no banco, CPU e memória da
  API. Vai para o resumo do job e para `$RUNNER_TEMP/condpulse-load-report.json` (impresso no log).
- Padrão no push: 100 rps por 60 s, 1.000 sessões, pool 2 (o mesmo da homologação). Disparo manual aceita até
  300 rps, 600 s, 2.000 sessões e pool 16.

**Limite**: API, banco e gerador dividem a mesma máquina, sem rede nem TLS real. O número serve para comparar versões e
achar regressão; **não declara capacidade** da homologação ou da produção, nem custo. A carga representativa depende de
ambiente dedicado, contas de teste e OK do responsável (ver BLOQUEADO no STATUS).

## Execuções

| Data | Commit | Run | Alvo | Obtido | p50 / p95 / p99 (ms) | Erros | 4xx de regra | Conexões | CPU API |
|---|---|---|---|---:|---|---:|---:|---:|---:|
| 30/10/2026 | `d0a2b64` | 41019237444 | 100 rps, 60 s, 1.000 sessões, pool 2 | 100 rps | 3,7 / 9,1 / 28,3 | 0 de 5.999 | 27 (25 janela inválida, 2 conflitos) | 2 | 0,38 núcleo |

Pior cauda nessa execução: cancelamento p99 140,2 ms e criação p99 67,8 ms. Números do runner; não comparar com metas
de produção.
