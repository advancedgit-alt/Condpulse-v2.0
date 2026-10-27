# ADR-0002 — Um banco principal: PostgreSQL
Data: 27/10/2026. Decisão adotada para o escopo P0/P1.

Supabase é a plataforma; PostgreSQL é o motor do banco.
Reservas, unidades, moradores, vínculos, visitas e encomendas dependem de relações,
transações, chaves estrangeiras e proteção de concorrência.
JSONB atende regras versionadas, configurações e payloads normalizados sem retirar
as constraints das entidades principais. Arquivos binários ficam no Storage privado.

MongoDB não tem requisito identificado no P0/P1. Não será provisionado.
Outbox, inbox e sessões começam no PostgreSQL, sem Redis obrigatório.
A capacidade depende de dimensionamento, planos de consulta, índices, pool, retenção
e carga real. Um banco adequado não significa capacidade infinita.

Reabrir a decisão apenas para carga/consulta concreta que justifique serviço especializado,
com custo, responsável, backup e fonte oficial dos dados definidos.
Fontes: https://www.postgresql.org/docs/17/rangetypes.html e
https://www.postgresql.org/docs/17/datatype-json.html
