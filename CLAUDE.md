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
- PRD CondPulse v1.0, 27/10/2026, em spec/documentacao (pacote em spec/, somente referência); decisões em docs/adr.
- Java25/Spring Boot4.1.2, monólito modular com API/BFF e worker separados.
- React/TypeScript/Vite na web e React Native/Expo no mobile.
- PostgreSQL como banco único principal, projetos Supabase exclusivos em São Paulo.
- Supabase Auth/Storage; homologacao da aplicacao na Vercel conforme ADR-0005, solicitada pelo responsavel. Coolify dedicado permanece alternativa. Sem MongoDB inicial.
- Flyway controla migrations. Runtime sem DDL/SUPERUSER/BYPASSRLS.

## Estado e pendências

- Resumo operacional vigente: [docs/ESTADO_ATUAL.md](docs/ESTADO_ATUAL.md).
- Evidências e histórico por entrega: [docs/STATUS.md](docs/STATUS.md); antes de executar, confira o estado remoto.
- SMTP Resend e worker outbox já foram configurados. V053/V054 já aplicadas em HML. Não tratar relatos antigos de ausência como pendência atual.
- Homologação e produção operacional são ambientes distintos. O nome Vercel Production não muda o banco de HML para produção.
- Somente chame uma jornada de homologada quando o percurso real e as negações forem comprovados.
- A rodada de 01/11 tem autorização para correções, testes e carga sintética na HML; os dados ficam preservados até pedido posterior de limpeza.
- Código de fundação, casca de painel, CI e readiness não equivalem ao produto completo entregue.
