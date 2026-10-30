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
- V001/V002 aplicadas atomicamente e atestadas pela excecao ADR-0003; baseline Flyway oficial002 e V003/V004/V005 aplicados e validados remotamente em 28/10/2026. V006-V017 aplicadas em 29/10/2026 com OK do responsável (V006-V015 migrate40861754667, V016 40866823651, V017 40868152258; repetições sem linha nova). V018 (importação de unidades, CP-016) aplicada em 29/10/2026 pelo executor protegido (migrate40951606378, repetição40951942737 sem linha nova). V019 (anexos privados de pedido, CP-017) aplicada em 29/10/2026 (migrate40958018877, repetição40958254298 sem linha nova); CP_FILES_ENABLED segue desligada até a chave de serviço do Storage ser cadastrada pelo responsável. V020 (link de convite, CP-037) aplicada em 29/10/2026 (migrate40976303864, repetição40976490093 sem linha nova). V021 (convidados de reserva, CP-038) aplicada em 29/10/2026 (migrate40981767427, repetição40982062417 sem linha nova). V022 (prestador recorrente, CP-039) aplicada em 29/10/2026 (migrate40983836465, repetição40984751973 sem linha nova). V023 (fila de espera, CP-040) aplicada em 29/10/2026 (migrate40993166940, repetição40993369928 sem linha nova; smoke40993532095). V024 (retirada delegada, CP-041) aplicada em 30/10/2026 (migrate41000850664, repetição41001042891 sem linha nova; smoke41001237346); delegação fica 503 até o responsável cadastrar CP_PARCEL_CODE_KEY. V025 (mudanças, CP-042) aplicada em 30/10/2026 (migrate41005335349, repetição41005493459 sem linha nova; smoke41005617433). Evidencias e runs em docs/STATUS.md.
- Três buckets privados e política de Auth configurados; conferir manifesto e STATUS.
- Login runtime exclusivo e configuração JDBC/CA aplicados na Vercel Production pelo apply40757578769, source2f1abf3547736c247fc082b2cc6ed76c18f2d38b. Em 29/10/2026, com autorização do responsável, BFF, reservas, gestão, plataforma, cobrança, encomendas, visitas, caixa interna (notificações), avisos do condomínio e pedidos foram ligados na Vercel Production, cada flag valendo a partir do deployment seguinte, com smoke público verde; recuperação por e-mail e worker seguem desligados. Onboarding de homologação aplicado em 29/10/2026 com aprovação do responsável (inspect40917252737, apply40917560570; docs/evidence/onboarding-hml-20261029.md); ensaio formal de Auth real e MFA pendente.
- O limite da role foi ajustado separadamente de 4 para 16 por SQL operacional revisado/CI 40774877934, com Hikari 2 e guardas de margem da ADR-0006; é orçamento provisório, sem aceite de capacidade.
- Smoke40777572663/source 5060fec7d4bcff0ae8df49801f1fecd55b7c0925 confirmou readiness 200/JSON/UP após uma consulta extra;503 inicial OUT_OF_SERVICE e /v1/me 401 esperado foram preservados. Prova JDBC pontual, sem homologação de login, cold start ou capacidade. Causa do 503 inicial corrigida no PR #52 (guarda da role antes de abrir a porta); smokes 40947509551 e 40951319384 com readiness 200/UP já na primeira consulta.
- Duas Secrets BFF e a Config publicável foram preparadas pelo apply40787895235, sourcecbb0cc12d6f97c79555037bc19c7e2269acb4e64, geração1eec1437-1cdc-4976-b086-000ae350425b: STAGED, com readback de metadados; valores Secret não foram lidos de volta.
- Segundo projeto Supabase criado após aceite explícito de US$10/mês: CondPulse-producao, tkxcujckpsuawnzkvpjg, sa-east-1, ACTIVE_HEALTHY. Inspeção confirmou banco de aplicação/Auth/Storage vazios; signup público desativado e SSL obrigatório ativado no painel. Inventário: docs/AMBIENTES.md. V001-V017 aplicadas pelo Flyway normal em 29/10/2026 (migrate40884195154), sem baseline. Produção tem só o schema: sem login runtime, Auth, Storage, origem ou aplicação ligada; a Vercel Production continua ligada à homologação.
- Integração completa do segundo ambiente (runtime, credenciais de menor privilégio, Auth/Storage, origem), SMTP, worker contínuo, rotação, PITR, restore e parceiro físico pendentes. Produção nunca recebe baseline legado nem cópia de segredos HML.
- Código de fundação não equivale ao produto P0/P1 implementado/homologado.

