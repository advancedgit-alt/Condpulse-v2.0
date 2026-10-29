# Backlog de implementação

68 tickets planejados. Dependências registram gates de fase, não substituem o refinamento técnico dos PRs. As janelas são metas de trabalho e não estimativas garantidas.

## F0 — Fundação (D0–D2)

### CP-001 — Congelar escopo e dois pilotos
Responsável: Produto. Estado: PLANEJADO. Dependências: nenhuma.

Entrega: Assinar matriz P0/P1, pacote e processos preservados por condomínio.
Aceite: Nenhuma função vendida fica sem responsável ou critério de aceite.

### CP-002 — Fixar toolchain e ADRs
Responsável: Arquitetura. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Java 25, Spring Boot compatível, Node LTS e Expo SDK compatível; arquivos de lock e SBOM.
Aceite: Build reproduzível de checkout limpo e relatório de dependências, sem versões flutuantes.

### CP-003 — Criar monólito modular e limites
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Pacotes por domínio com testes de dependência; controllers não acessam repositórios de outros módulos.
Aceite: Teste de arquitetura falha em dependência circular ou acesso proibido.

### CP-004 — Provisionar ambientes isolados
Responsável: DevOps. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Projetos e segredos separados de desenvolvimento, homologação e produção CondPulse.
Aceite: Dados e credenciais de um ambiente não acessam outro; nenhum recurso Advanced é alterado.

### CP-005 — Executar migrações de referência
Responsável: Banco. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Validar V001/V002 em Postgres descartável compatível; ajustar migrations por revisão.
Aceite: Execução limpa, constraints testadas e nenhuma alteração manual fora de migration.

### CP-006 — Implementar contexto e RLS
Responsável: Segurança. Estado: PLANEJADO. Dependências: CP-001.

Entrega: JWT, conta ativa, tenant validado e contexto transacional no pool.
Aceite: Requests alternando tenants não herdam contexto; role comum não tem BYPASSRLS.

### CP-007 — Criar design system CondPulse
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Tokens, botões, formulário, tabelas, modais, status, navegação e estados de erro.
Aceite: Contraste verificado, foco por teclado e toques de 48 px nas ações principais.

### CP-008 — Abrir homologação do parceiro
Responsável: Integração. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Obter documentação, contrato, sandbox, modelo/firmware e integrador nomeado.
Aceite: Disponibilidade e licença verificadas; sem isso facial segue desabilitado e gate é registrado.

### CP-009 — Configurar pipeline protegido
Responsável: DevOps. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Testes, análise estática, lockfiles, scanner e artefatos por commit.
Aceite: PR externo não lê segredo nem publica; release exige aprovação e artefato identificado.

### CP-010 — Preparar cadastros e autorização legal
Responsável: Produto. Estado: PLANEJADO. Dependências: CP-001.

Entrega: Modelo de unidades, responsáveis, aviso de privacidade e fluxo de alternativa não biométrica.
Aceite: Condomínio valida responsáveis, finalidade e procedimento antes de importar dados reais.

## F1 — Identidade e cadastro (D3–D5)

### CP-011 — Sessão web BFF
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Login, cookie HttpOnly, CSRF, renovação, expiração e logout.
Aceite: Tokens não ficam no localStorage; logout invalida sessão; requests CSRF falham.

### CP-012 — Sessão nativa segura
Responsável: Mobile. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Integração Auth com armazenamento seguro e limpeza de dados no logout.
Aceite: Reinstalação, token expirado e troca de usuário não mostram dados anteriores.

### CP-013 — MFA de operadores
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Exigir AAL2 nas ações privilegiadas e procedimento de recuperação auditado.
Aceite: Token AAL1 de porteiro/gestor não autoriza operação protegida.

### CP-014 — Vínculos e capacidades
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Morador, titular, operador, gestor, suporte temporário; vigência por unidade.
Aceite: Conhecer número da unidade não permite entrar nela; revogação bloqueia novas operações.

### CP-015 — Troca segura de contexto
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Selecionar apenas vínculos ativos e limpar cache por usuário/tenant/unidade.
Aceite: Resposta atrasada do contexto anterior não aparece na nova unidade.

### CP-016 — Importação em staging
Responsável: Banco. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: CSV, preview, validação, duplicatas, erros por linha e aprovação de lote.
Aceite: Repetir o mesmo lote não duplica unidades ou vínculos; origem e contagens são registradas.

### CP-017 — Upload privado e download autorizado
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Intenção, tamanho/MIME, quarentena, conclusão e link curto após autorização.
Aceite: Objeto de outro tenant e arquivo com tipo inválido são negados; upload não vira público.

### CP-018 — Auditoria mínima de ações
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Ator, escopo, ação, objeto, motivo e correlação; segredos/fotos fora dos logs.
Aceite: Mudanças sensíveis têm trilha; role comum não edita nem apaga auditoria.

### CP-019 — Testar isolamento de identidade
Responsável: QA. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Matriz de papéis e tenants incluindo inválido, suspenso, expirado e suporte.
Aceite: Todos os testes negativos passam nas rotas, arquivos e stream.

### CP-020 — Onboarding e recuperação
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-001, CP-002, CP-003, CP-004, CP-005, CP-006, CP-007, CP-008, CP-009, CP-010.

Entrega: Convite validado, primeiro acesso, tutorial curto, recuperação e troca de dispositivo.
Aceite: Usuário convidado conclui ativação sem intervenção de banco; convite expirado não cria vínculo.

## F2 — Jornadas essenciais (D6–D9)

### CP-021 — Configuração de áreas e regras
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Cadastro de área, horário, capacidade, taxa informativa, buffer e versão de regulamento.
Aceite: Regra inválida é rejeitada e edição não reescreve silenciosamente reservas existentes.

### CP-022 — Disponibilidade e reserva transacional
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Constraint de intervalo efetivo, idempotência e versão das regras aceitas.
Aceite: Teste concorrente confirma uma única reserva bloqueante no mesmo período.

### CP-023 — Aprovação, rejeição e cancelamento
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Estados, autorização do gestor, prazo, cancelamento e propagação aos convites.
Aceite: Decisão repetida não duplica evento; cancelamento desativa convites associados.

### CP-024 — Reserva no celular
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Área, calendário, horário, regras, taxa e estado da solicitação.
Aceite: Pedido pendente não é mostrado como confirmado; conflito oferece nova disponibilidade.

### CP-025 — Autorização de visitante
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Identidade mínima, responsável, validade e revogação.
Aceite: Token de convite não expõe CPF/unidade e não pode autorizar fora do prazo.

### CP-026 — Chegada sem convite
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Solicitação vinculada à pessoa/unidade; decisão única com prazo.
Aceite: Push sem resposta não libera visita; resposta antiga não decide nova chegada.

### CP-027 — Fila operacional de portaria
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Busca, chegadas, autorizações, ocorrências e ações contextuais.
Aceite: Não há botão que marque entrada de visitante ainda não autorizado.

### CP-028 — Registro de acesso manual
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Direção, evidência manual, operador e motivo de correção por evento adicional.
Aceite: Entrada manual não é rotulada como passagem detectada por sensor.

### CP-029 — Recebimento de encomendas
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Unidade, volumes, posição de guarda, foto opcional e outbox.
Aceite: Clique repetido com mesma chave gera apenas um registro e uma intenção de notificação.

### CP-030 — Retirada de encomendas
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Transação de entrega, recebedor, operador e constraint única.
Aceite: Duas retiradas simultâneas produzem um sucesso e um conflito, sem sobrescrever histórico.

### CP-031 — Painel de encomendas
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Listas, detalhes e confirmação explícita da pessoa recebedora.
Aceite: Lista do morador inclui somente entregas autorizadas; portaria filtra por posição e unidade.

### CP-032 — Publicação de avisos e documentos
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Rascunho, público-alvo, publicação e registro de abertura.
Aceite: Rascunho não aparece para morador e abertura não é tratada como ciência jurídica.

### CP-033 — Pedidos e conversa vinculada
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Solicitação, mensagem, anexo, responsável e atualização de estado.
Aceite: Conversa permanece no pedido certo; acesso indevido por ID conhecido é negado.

### CP-034 — Outbox e worker duráveis
Responsável: Infra. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Lease, backoff, dead-letter operacional, correlação e replay seletivo.
Aceite: Interrupção entre efeito e ack é testada; efeitos não idempotentes não recebem retry cego.

### CP-035 — Push e caixa interna
Responsável: Infra. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: Consentimento, instalações, preferências, receipts e falhas.
Aceite: Provider aceitou não vira morador visualizou; token inválido é desativado.

### CP-036 — Atualizações em tempo real
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-011, CP-012, CP-013, CP-014, CP-015, CP-016, CP-017, CP-018, CP-019, CP-020.

Entrega: SSE autenticado e consulta de estado na reconexão.
Aceite: Revogação fecha stream; eventos não são transmitidos a usuários sem autorização.

## F3 — Completo e integração (D10–D12)

### CP-037 — Convites compartilháveis de uso controlado
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Token opaco com validade, hash no banco e limites de tentativas.
Aceite: Token bruto não aparece em logs, analytics nem URL persistida.

### CP-038 — Convidados de uma reserva
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Autorizações derivadas da janela do evento e do responsável.
Aceite: Reserva cancelada deixa todos os convites dependentes revogados, inclusive após retry.

### CP-039 — Prestador recorrente
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Dias locais, horas, vigência, revogação e expansão da próxima janela.
Aceite: Acesso fora de dia/horário falha; fuso da unidade não depende do celular.

### CP-040 — Fila de espera
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Ordem estável, oferta com prazo e reserva de slot durante oferta.
Aceite: Duas ofertas não capturam o mesmo slot; oferta vencida avança a fila sem dupla confirmação.

### CP-041 — Retirada delegada e código
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Delegação por encomenda, validade, código com hash e limite de tentativas.
Aceite: Código gasto não é reutilizado; delegação de outra encomenda não é aceita.

### CP-042 — Mudança e entrega de móveis
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Formulário específico, período e aprovação, avisos à portaria.
Aceite: Aprovação não promete reserva de elevador inexistente; regras configuradas são exibidas.

### CP-043 — Adaptador simulado de acesso
Responsável: Integração. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Implementar contrato interno com cenários de timeout, duplicata e confirmação parcial.
Aceite: Testes determinísticos cobrem cada estado sem contato com dispositivo real.

### CP-044 — Adaptador real do parceiro
Responsável: Integração. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Mapeamento documentado de operações e erros para contrato interno.
Aceite: Somente endpoints documentados; sandbox confirma operações, idempotência e limites.

### CP-045 — Cadastro e atualização facial
Responsável: Integração. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Revisão, legalidade, destinatários, envio e confirmação por versão/dispositivo.
Aceite: ACK de recebimento não gera estado ativo; uma falha aparece por destino.

### CP-046 — Revogação prioritária
Responsável: Integração. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Estado desejado, prioridade, reconciliação e tratamento de callback antigo.
Aceite: Confirmação de versão antiga não reativa credencial revogada.

### CP-047 — Inbox autenticada e eventos
Responsável: Integração. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Assinatura do parceiro, timestamp, deduplicação e mapeamento de evidência/direção.
Aceite: Callback de conector A não escreve tenant B; repetição não duplica evento.

### CP-048 — Saúde e reconciliação
Responsável: Integração. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Heartbeat, relógio, backlog e comparação do estado desejado/aplicado.
Aceite: Equipamento offline aparece como offline; reconciliação recupera estado final, não reproduz fila obsoleta.

### CP-049 — Histórico residencial protegido
Responsável: Frontend. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Mostrar acessos pertinentes e distinguir autorização, abertura e passagem.
Aceite: Sem identificador de saída, exibir sem registro de saída; nada de presença inferida.

### CP-050 — Ciclo de vida biométrico
Responsável: Segurança. Estado: PLANEJADO. Dependências: CP-021, CP-022, CP-023, CP-024, CP-025, CP-026, CP-027, CP-028, CP-029, CP-030, CP-031, CP-032, CP-033, CP-034, CP-035, CP-036.

Entrega: Upload transitório, remoção, alternativa não facial e pedidos do titular.
Aceite: Purge e exclusão no parceiro têm evidência; falha gera pendência, não sucesso falso.

## F4 — Homologação e piloto (D13–D15)

### CP-051 — Teste de concorrência e banco
Responsável: QA. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Executar constraints, migrações, replay e pools na mesma família Postgres do destino.
Aceite: Nenhuma reserva ou retirada duplicada; sem dependência em H2 para integridade.

### CP-052 — Teste de carga representativo
Responsável: QA. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: 100 rps/1.000 sessões como carga-alvo inicial; misturar leituras e gravações.
Aceite: Relatório p50/p95/p99, erros, saturação e custo; comparar com metas, sem declarar capacidade sem dados.

### CP-053 — Teste mobile e acessibilidade
Responsável: QA. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Aparelhos Android/iOS reais, fontes ampliadas, leitor de tela e rede instável.
Aceite: Jornadas críticas usáveis com teclado/leitor e sem botões inacessíveis.

### CP-054 — Homologar instalação física
Responsável: Integrador. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Cadastro, expiração, bloqueio, sensor, retorno de energia/rede e cada turno.
Aceite: Modelo/firmware registrados e termo de aceite assinado; preservar saída de emergência.

### CP-055 — Restaurar banco e objetos
Responsável: DevOps. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Restore em ambiente isolado, validar contagens, vínculos e hashes de arquivos.
Aceite: Cronometrar RTO/RPO reais; não religar integrações antes de reconciliar revogações.

### CP-056 — Revisão de ameaças e segredos
Responsável: Segurança. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Isolamento, BOLA, CSRF, upload, callbacks, logs e cofre.
Aceite: Nenhum achado crítico/alto aberto; nenhum segredo detectado no repositório.

### CP-057 — Treinar todos os turnos
Responsável: Produto. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Roteiro de chegada, encomenda, falha de internet e escalonamento.
Aceite: Cada turno executa as tarefas sem depender de agente de IA.

### CP-058 — Reconciliar dados migrados
Responsável: Operação. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Cadastros, reservas futuras, encomendas pendentes e permissões válidas.
Aceite: Contagens e exceções aprovadas pelo condomínio; lote rastreável.

### CP-059 — Pilotar condomínio sem sistema
Responsável: Produto. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Ativar escopo Essencial com suporte nomeado e observação.
Aceite: Conclusões/erros medidos por jornada e autorização de continuidade registrada.

### CP-060 — Pilotar condomínio em substituição
Responsável: Produto. Estado: PLANEJADO. Dependências: CP-037, CP-038, CP-039, CP-040, CP-041, CP-042, CP-043, CP-044, CP-045, CP-046, CP-047, CP-048, CP-049, CP-050.

Entrega: Cutover por processo e fornecedor anterior preservado até aceite.
Aceite: Uma agenda oficial, continuidade do acesso e rollback acordado.

## F5 — Estabilização e liberação (D16–D20)

### CP-061 — Corrigir defeitos do piloto
Responsável: Backend. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Classificação por risco, reprodução e regressão automatizada.
Aceite: Incidentes críticos resolvidos antes de expansão; nenhuma correção sem teste associado.

### CP-062 — Preparar distribuição
Responsável: Mobile. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Assinatura, builds, política de privacidade, contas e submissão às lojas.
Aceite: Build de teste instalável; data de aprovação externa não é tratada como garantida.

### CP-063 — Formalizar atendimento e contratos
Responsável: Operação. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Matriz software/hardware, horários, severidade e escalonamento.
Aceite: Cliente sabe quem atende falha de leitor versus software e quais custos foram contratados.

### CP-064 — Concluir aceite do Completo
Responsável: Produto. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Revisar fila de espera, delegação, recorrência e integração vendida.
Aceite: Função sem aceite fica desabilitada e fora de promessa comercial.

### CP-065 — Medir margem e suporte
Responsável: Financeiro. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Custos reais de infraestrutura, parceiro, comunicação e minutos de atendimento.
Aceite: Preço confrontado com custo por condomínio, sem chamar faturamento de lucro.

### CP-066 — Publicar release por artefato
Responsável: DevOps. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Promover digest homologado, migrations compatíveis e smoke em produção.
Aceite: Versão rastreável, monitoramento ativo e rollback de aplicação demonstrado.

### CP-067 — Aplicar gate de lançamento
Responsável: Produto. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Revisar todos os critérios D20 com responsáveis e evidências.
Aceite: Liberar apenas escopo aprovado; não usar a data para ignorar teste ou homologação.

### CP-068 — Preparar próxima implantação
Responsável: Operação. Estado: PLANEJADO. Dependências: CP-051, CP-052, CP-053, CP-054, CP-055, CP-056, CP-057, CP-058, CP-059, CP-060.

Entrega: Checklist replicável, inventário, treinamento e limites de capacidade.
Aceite: Terceiro condomínio usa o mesmo procedimento; exceções viram tarefas, não alterações manuais.
