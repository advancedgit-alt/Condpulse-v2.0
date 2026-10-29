# CondPulse — pacote de especificação e implementação v1.0

Data: 27/10/2026. Estado: **DOCUMENTADO, NÃO IMPLANTADO**.

## Abrir primeiro

`documentacao/CondPulse_PRD_Arquitetura_v1.0.pdf` é a leitura principal. DOCX editável e Markdown acompanham o mesmo texto. Os capítulos 37–40 detalham dados, API, tickets e aceite.

## Conteúdo

- PRD com produto, 2 pacotes, jornadas, arquitetura por camadas, segurança, operações e fases.
- 9 diagramas PNG/SVG e fontes Graphviz DOT: contexto, deploy, organograma, módulos, dados, facial, reserva e roadmap.
- Mockups de portaria e morador; marca vetorial, PNG e tokens visuais.
- 41 tabelas de referência (38 de negócio/tenant e 3 de plataforma), migrations e políticas RLS.
- OpenAPI inicial com 59 operações/58 schemas, mais contrato interno de adaptador de acesso.
- 68 tickets planejados e 60 cenários de aceite NÃO EXECUTADOS.
- Runbooks de implantação, contingência, restore e cutover; .env/compose ilustrativos sem credenciais.
- Instruções para agentes de IA, prompt inicial e Definition of Done.
- Pesquisa histórica dos concorrentes e fontes oficiais com data de consulta.

## O que não foi feito nesta entrega

Não há aplicação Java/React executável, containers publicados, contas provisionadas, deploy, submissão às lojas, contrato de fornecedor ou homologação física. SQL de referência não foi executado em PostgreSQL. YAML/JSON, referências, estrutura e diagramação recebem validações documentais registradas em `qualidade/RELATORIO_VALIDACAO.md`; isso não equivale a testes do produto.

## Como iniciar com outra IA

Anexar o ZIP, extrair, ler este README e `handoff/PROMPT_INICIAL.md`, depois `handoff/AGENTS.md`. Trabalhar por ticket e evidência. Não começar com uma geração monolítica de todas as telas sem identidade, isolamento, constraints e testes.

## Decisões

Java/Spring Boot; React/TypeScript/Vite web; React Native/Expo app; PostgreSQL/Supabase gerenciado dedicado para Auth/dados/Storage; Coolify para aplicação/worker em recursos dedicados; um parceiro de facial por primeira homologação. Confirmar versões/paches no D1. Não reutilizar banco ou segredos da Advanced.

O plano D15/D20 é um alvo condicionado a gates. A integração física não é prometida como universal ou livre de falhas. Valores R$249/R$499 são hipóteses comerciais, não custo total nem lucro garantido.
