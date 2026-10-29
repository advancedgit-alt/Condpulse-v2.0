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
- docs/STATUS.md registra evidências e limitações.
- Projeto Condpulse-v2.0 encontrado ativo em São Paulo: rpnrqfrfwywglvyykgrg, PostgreSQL17.11.0.003.
- V001/V002 aplicadas atomicamente e atestadas pela excecao ADR-0003; baseline Flyway oficial002 e V003/V004/V005 aplicados e validados remotamente em 28/10/2026. Evidencias e runs em docs/STATUS.md.
- Três buckets privados e política de Auth configurados; conferir manifesto e STATUS.
- Login runtime exclusivo e configuração JDBC/CA aplicados na Vercel Production pelo apply40757578769, source2f1abf3547736c247fc082b2cc6ed76c18f2d38b. BFF/reservas permanecem false; Auth real e onboarding continuam pendentes.
- O limite da role foi ajustado separadamente de 4 para 16 por SQL operacional revisado/CI 40774877934, com Hikari 2 e guardas de margem da ADR-0006; é orçamento provisório, sem aceite de capacidade.
- Smoke40777572663/source 5060fec7d4bcff0ae8df49801f1fecd55b7c0925 confirmou readiness 200/JSON/UP após uma consulta extra;503 inicial OUT_OF_SERVICE e /v1/me 401 esperado foram preservados. Prova JDBC pontual, sem homologação de login, cold start ou capacidade.
- Duas Secrets BFF e a Config publicável foram preparadas pelo apply40787895235, sourcecbb0cc12d6f97c79555037bc19c7e2269acb4e64, geração1eec1437-1cdc-4976-b086-000ae350425b: STAGED, com readback de metadados; valores Secret não foram lidos de volta. BFF/reservas continuam false; onboarding aprovado e Auth real permanecem pendentes.
- Segundo projeto Supabase criado após aceite explícito de US$10/mês: CondPulse-producao, tkxcujckpsuawnzkvpjg, sa-east-1, ACTIVE_HEALTHY. Inspeção confirmou banco de aplicação/Auth/Storage vazios; signup público desativado e SSL obrigatório ativado no painel. Inventário: docs/AMBIENTES.md. Isso não configura a aplicação nem conclui a separação dos ambientes.
- Integração completa do segundo ambiente, SMTP, worker contínuo, credenciais operacionais de onboarding, rotação, PITR, restore e parceiro físico pendentes. Produção nova exige Flyway normal V001-V005, sem baseline legado ou cópia de segredos HML.
- Código de fundação não equivale ao produto P0/P1 implementado/homologado.

