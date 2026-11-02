# Plano técnico e de implantação
Versão de execução 0.1 — 27/10/2026. Referência: PRD CondPulse v1.0.
Decisão técnica, implementação e homologação são estados diferentes.

Estado operacional conferido em 01/11/2026: web na Vercel, API e worker contínuo na Fly (gru), banco HML até V056. SMTP Resend do Auth, outbox, fila de espera e alertas de permanência já configurados; demo carregado e jornadas reais registradas. Este plano nasceu em 27/10 e suas estimativas não substituem o estado atual: [ESTADO_ATUAL.md](ESTADO_ATUAL.md).

Execução atual por fases e critérios: [PLANO_FINALIZACAO_HML.md](PLANO_FINALIZACAO_HML.md). Migrações/publicações têm recibos no estado atual; não repetir configurações antigas.

## 1. Objetivo e limite
Morador: vínculo, reserva, convite, encomenda, avisos, documentos e pedidos.
Portaria: fila Agora/Previstos, chegada, conferência, retirada e contingência.
Gestão: regras locais, vínculos, aprovações, exceções e saúde das integrações.
Operação SaaS: contrato, capacidade e suporte temporário auditado.
Sem contabilidade, folha, boleto, engenharia ou reprodução do app-advanced.
Essencial R$249 e Completo R$499 são hipóteses comerciais do PRD, sujeitas a custo/aceite.
Hardware, instalação, portaria humana, SMS e licenças não estão presumidos incluídos.
Cobrança em atraso nunca bloqueia automaticamente passagem física do morador.

## 2. Tecnologia por camada
| Camada | Tecnologia/serviço | Responsabilidade |
| --- | --- | --- |
| API/BFF | Java25/Spring Boot4.1.2 | Regra, autorização, transação, sessão web e contratos /v1 |
| Worker | Mesma release Java, outro processo | Expirações, outbox/inbox, avisos e reconciliação |
| Migração | Flyway, job separado | Schema com credencial DDL exclusiva |
| Web | React/TS/Vite/Router/Query | Morador, portaria e gestão; cache por contexto |
| Mobile | React Native/Expo | Auth seguro, câmera, deep link e push |
| Dados | PostgreSQL/Supabase dedicado | Integridade, tenancy, sessão durável, auditoria e filas |
| Auth | Supabase Auth | Identidade, MFA; permissões locais permanecem na API |
| Arquivos | Supabase Storage privado | Quarentena, autorização, links curtos e retenção |
| Deploy HML | Vercel (web) e Fly (API/worker), gru | Imagem revisada, limites e health checks; Coolify permanece alternativa |
| Física | Um parceiro contratado inicialmente | Aplicação confirmada por equipamento/firmware |
| CI/código | GitHub; Woodpecker preferencial | Revisão, testes, imagem, SBOM, staging e promoção |
| Segredos | Cofre do ambiente | Menor privilégio, rotação e recuperação |

## 3. Ambientes e custo conhecido
Organização conectada: Sindico Advanced. Projetos CondPulse próprios.
Região específica: sa-east-1 (São Paulo), não apenas agrupamento Americas.
Homologação e produção precisam de projetos/buckets/Auth/credenciais diferentes.
Local usa PostgreSQL descartável e dados sintéticos.

Consulta de custo da organização em 27/10/2026:
US$10/mês por novo projeto, total-base incremental US$20/mês para dois projetos.
Consumo adicional, PITR, SMTP, domínio, lojas e servidor não estão nesse total.
O projeto Condpulse-v2.0 (rpnrqfrfwywglvyykgrg) foi posteriormente encontrado ACTIVE_HEALTHY em sa-east-1, com PostgreSQL17.11.0.003. Não atribuir sua criação a esta execução: a chamada anterior foi interrompida. Em etapa posterior, após aceite explícito de US$10/mês de base, o segundo projeto CondPulse-producao (tkxcujckpsuawnzkvpjg) foi criado e conferido ACTIVE_HEALTHY na mesma região. A consulta de 01/11 confirmou produção somente até V017, sem usuários Auth, buckets ou logins runtime/worker; aplicação e integração de produção ainda não estão prontas. A separação completa dos ambientes permanece pendente. Inventário e evidência: [AMBIENTES.md](AMBIENTES.md).
Não registrar projeto como provisionado sem retorno e read-back ACTIVE_HEALTHY.
Não habilitar adicional pago sem aceite específico do custo.

## 4. Arquitetura por módulos
Identidade; tenancy/vínculos/unidades; reservas; visitantes/chegadas; encomendas;
comunicação/documentos/pedidos; portaria; acesso físico; notificações;
auditoria/suporte/importação.
Dentro de cada módulo: api/application/domain/infrastructure.
Domínio não depende de Spring/HTTP, Supabase, SDK de parceiro ou componentes React.
Não escrever diretamente em tabela de outro domínio sem serviço/evento publicado.
API e worker compartilham release. Worker sem porta pública.
Build fora do host de produção; publicar/promover imagem por digest.

## 5. Contratos e dados
Schemas platform (contas/contexto) e cp (negócio), sem exposição ao cliente na Data API.
Flyway é a única origem de migrations; API não usa ddl-auto=update ou migration no startup.
Role migradora separada; API/worker sem SUPERUSER/BYPASSRLS/DDL.
Todas as FKs de negócio incluem tenant_id. ENABLE + FORCE RLS.
Na mesma conexão/transação:
set_config('app.tenant_id',tenant_validado,true) e set_config('app.actor_id',ator_validado,true).
Pool reutilizado nunca herda contexto. RLS isola tenant; API aplica unidade/papel/finalidade.
Reservas: EXCLUDE GiST [início,fim), limpeza incluída; cota por unidade sob trava.
Encomendas: UNIQUE no handoff e transação de estado.
Chegada: primeira decisão válida por versão/prazo; sem resposta nunca autoriza.
Cancelamento: estado, auditoria, revogações dependentes e outbox atômicos.
Idempotência por tenant/ator/método/rota/chave, hash de corpo e resultado autorizado.
Mesma chave/corpo retorna o mesmo resultado; corpo diferente gera 409.
Migrations de fundação não equivalem à implementação das jornadas.

## 6. Login e autorização
Supabase Auth é identidade, não a fonte dos papéis de condomínio.
Assinatura, issuer, audience, expiração, subject e conta ativa são validados.
Vínculo precisa estar vigente em toda operação, mesmo com JWT ainda válido.
Web: BFF mantém tokens cifrados no servidor e cookie opaco __Host- Secure/HttpOnly/SameSite=Lax.
Alterações pelo BFF têm CSRF; refresh token não vai a localStorage.
Mobile: provedor nativo, PKCE quando aplicável e armazenamento seguro.
Gestor/suporte/admin exigem AAL2/TOTP no backend. Portaria usa conta individual/dispositivo autorizado.
Conhecer apartamento não concede vínculo. Convite e importação precisam de revisão.
Canal inicial proposto: e-mail/senha e recuperação por e-mail com retorno neutro.
SMS só após contratação/teste. WhatsApp automático está fora do lançamento.

## 7. Interface e arquivos
Tokens CondPulse: navy #102A43, teal #007F86, fundo #F4F7FA; Lato/fallback;
alvos48px, foco visível, zoom200%, teclado e leitor de tela.
Cache inclui conta/tenant/unidade. Troca cancela requisições e apaga conteúdo anterior.
Confirmações de reserva, retirada, autorização e físico aguardam estado real do servidor.
Estados: carregando/vazio/negado/offline/antigo/conflito/parcial/concluído/erro.
Upload com tamanho/MIME/quarentena/análise; bucket privado; API revalida vínculo antes do download.
Sem fotos biométricas reais em desenvolvimento ou prompts.

## 8. Filas, notificações e integração física
Outbox/inbox transacionais; entrega ao menos uma vez, não exatamente uma vez.
Lease, retry seguro, backoff/jitter, idempotência e falha visível.
Eventos mínimos para SSE, reautorização e ressincronização após reconexão.
Push genérico na tela bloqueada. Aceitação pelo provedor não é entrega/leitura/aprovação.
Primeiro um parceiro e uma combinação de modelo/firmware homologada.
Bravas é candidata, não fornecedora contratada; nenhum endpoint será inventado.
Credencial usa desired_version/applied_version e confirmação por destino.
Callback antigo não reativa revogação. Timeout pode ser UNKNOWN e exigir consulta.
Relé acionado não prova pessoa entrando; saída não identificada não prova permanência.
Abertura remota e facial começam desligados. Sem replay de comando antigo após voltar a rede.
Modo local/offline e saída de emergência exigem integrador e contingência aprovados.

## 9. Infraestrutura e capacidade
Host São Paulo dedicado de referência: 4vCPU/8GiB, ainda a cotar e testar.
Hipótese inicial: API teto2GiB, worker1GiB; reservar sistema/proxy/observabilidade.
Pools propostos API8/worker4/migrador2; verificar limites contratados e soma de conexões.
TLS PostgreSQL com CA/hostname; escolher conexão direta ou pooler de sessão após ensaio de rede.
Não usar estado de sessão/prepared statements incompatíveis com pool transacional.
Host único é ponto único de falha, não um SLA de HA.
Metas PRD: leituras p95<=400ms, comandos<=800ms, portaria<=3s;
ensaio100rps/1.000sessões. São metas não medidas, não capacidade já garantida.

## 10. CI e entrega
Woodpecker preferencial: vinculação/capacidade/isolamento pendentes de verificação.
Runner não compartilha segredos nem Docker socket da produção.
Fundação: build Java, testes unitários, PostgreSQL real, isolamento/concorrência e contratos.
Evolução: lint/typecheck/build web/mobile, cliente gerado, Playwright, dependências,
scanner de segredos, container/SBOM, staging, smoke e promoção aprovada.
GitHub Actions pode validar a fundação em runner Ubuntu padrão no repositório público,
sem segredos/deploy/cache/artefatos persistentes. Desativar execução duplicada quando Woodpecker assumir.
Se ficar privado, revisar custo antes de manter o executor.

## 11. Backup e operação
Meta de banco RPO<=15min exige PITR ou equivalente comprovado. Backup diário não atende.
PITR é adicional separado; confirmar compute mínimo e cotação antes de contratar.
Backup do banco Supabase não contém bytes do Storage: cópia externa/versionada é separada.
Meta objetos RPO<=1h e RTO<=4h depende de exercício cronometrado.
Logs sem tokens/rostos/corpos sensíveis. Health não prova restore nem jornada.
Não anunciar backup, restauração ou disponibilidade sem evidência.

## 12. Execução e gates
D0: orçamento, ambientes, responsáveis, pilotos, domínio, parceiro e privacidade.
Fundação: scaffold, versões, contratos, migrations, testes e CI.
Fatia vertical: login -> tenant/unidade -> área -> reserva -> evento -> portaria.
P0: visitantes, chegadas, encomendas, avisos, documentos, pedidos e importação.
P1: recorrência, delegação, fila, mudanças e integração homologada.
Homologação: isolamento, concorrência, carga, restore, dispositivos e turnos.
Piloto: uma agenda oficial por processo, fornecedor anterior preservado se necessário.
Produção: digest aprovado, migration job, readiness, smoke, tráfego, worker e observação.
Todos os P0 aceitos; zero acesso cruzado/dupla operação; recuperação comprovada;
suporte e contratos definidos. Facial só com contrato/base legal/teste físico por modelo.
D1 não começa com D0 incompleto; D20 do PRD é meta condicionada, não promessa.
