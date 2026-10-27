# ADR-0001 — Arquitetura inicial
Data: 27/10/2026. Decisão técnica adotada; provisionamento ainda pendente.
Base: PRD CondPulse v1.0, capítulos 1, 13–18, 22 e 28.

## Stack
Java 25 LTS/Spring Boot 4.1.2, monólito modular orientado a funcionalidades.
API/BFF e worker separados como processos; imagem e release compartilhadas.
React/TypeScript/Vite, React Router, TanStack Query. Mobile React Native/Expo.
PostgreSQL gerenciado em Supabase exclusivo. Auth e Storage do mesmo ambiente.
Coolify para aplicação em host exclusivo São Paulo. Não reutilizar produção Advanced.

## Alternativas
| Opção | Avaliação | Decisão |
| --- | --- | --- |
| Coolify + Supabase gerenciado | Aplicação e dados próximos em São Paulo; banco/Auth/Storage gerenciados; host exige operação | Base adotada do PRD |
| Railway + Supabase | Containers gerenciados; regiões publicadas não incluem Brasil; medir API-banco e custo por uso | Alternativa, sem contratação |
| Supabase self-hosted | Mais controle, mas upgrades/Auth/Storage/restore aumentam trabalho inicial | Adiado |
| PostgreSQL + outro Auth/Storage | Viável, muda contratos e aumenta escopo | Reabrir somente com justificativa medida |
| Banco/host Advanced | Mistura dados e disputa capacidade com sistema em uso | Não adotar |
| Microserviços/Redis/Kafka | Complexidade antecipada sem demanda medida | Não adotar no início |

## Consequências
Um host não é alta disponibilidade. Pool e sessão durável precisam suportar futura réplica.
Spring 4.1.2/Java25 foram conferidos na documentação oficial; build/testes são gate separado.
Web/mobile terão versões fixadas em lockfiles no ticket CP-002; ainda não estão compilados.
Sem LLM no caminho de reserva, identidade, retirada ou acesso físico.
Abertura remota desabilitada e reconhecimento facial delegado ao parceiro homologado.
Rever se custo, região, carga ou requisitos de disponibilidade justificarem.

## Referências oficiais consultadas
- https://docs.spring.io/spring-boot/system-requirements.html
- https://docs.railway.com/deployments/regions
- https://supabase.com/docs/guides/platform/regions
- https://coolify.io/docs/applications/builds/docker-compose
