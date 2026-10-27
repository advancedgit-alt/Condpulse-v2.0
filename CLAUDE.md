# Claude Code — CondPulse v2.0

Instruções persistentes do projeto. Consulte também AGENTS.md.

## Como trabalhar
- Português do Brasil. Fuso America/Sao_Paulo.
- Não inventar números, datas, nomes ou fatos; pendências ficam explícitas.
- Nunca dizer "está feito" sem prova de teste, comando ou página conferida.

## Segurança
- Nunca mostrar, copiar ou commitar chave, token ou senha reais.
- Arquivos .env* ficam fora do Git; exemplos contêm somente placeholders.
- Gasto com serviço pago só com OK explícito do responsável.
- Não alterar recursos, dados ou segredos da Advanced.

## Git
- Branch própria → PR → revisão → merge.
- Sem force push ou reescrita de histórico compartilhado.

## Base técnica
- PRD CondPulse v1.0, 27/10/2026; decisões em docs/adr.
- Java25/Spring Boot4.1.2, monólito modular com API/BFF e worker separados.
- React/TypeScript/Vite na web e React Native/Expo no mobile.
- PostgreSQL como banco único principal, projetos Supabase exclusivos em São Paulo.
- Supabase Auth/Storage; aplicação no Coolify dedicado. Sem MongoDB inicial.
- Flyway controla migrations. Runtime sem DDL/SUPERUSER/BYPASSRLS.

## Estado e pendências
- docs/STATUS.md registra evidências e limitações.
- Projeto Condpulse-v2.0 encontrado ativo em São Paulo: rpnrqfrfwywglvyykgrg, PostgreSQL17.11.0.003.
- Custo-base consultado US$10/mês/projeto; dois projetos US$20/mês adicionais, sem opcionais.
- Migrations de negócio via Flyway ainda não aplicadas no projeto remoto; ver STATUS.
- Segundo ambiente, buckets privados, Auth/SMTP, host dedicado, cofre, PITR, restore e parceiro físico pendentes.
- Código de fundação não equivale ao produto P0/P1 implementado/homologado.
