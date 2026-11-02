# Demo de homologação: configuração e carga

Estado em 01/11/2026: CP_DEMO_ENABLED=true na Vercel e na Fly, API publicada; geração1 criada pela Central, tenant dcb28363-d009-46a7-8be4-ffb0795d1927. Carga32 unidades/3 áreas e demais registros conferidos; zero vínculos automáticos da conta real. A inspeção41321868132 registrava ausência antes da correção, não o estado vigente. Recibos e limites em [ESTADO_ATUAL.md](ESTADO_ATUAL.md).

## Vínculos explícitos do demo — continuação de 01/11/2026

O responsável confirmou no chat os papéis da própria conta, limitados à geração1 e ao parceiro fictício. Foram exercitados os fluxos normais da aplicação, sem escrita direta de autorização no SQL:

| Papel | Estado observado | Próximo requisito |
| --- | --- | --- |
| Gestão do condomínio demo | ACTIVE; convite da Central aceito pela própria conta; gestão com AAL2 aberta | Nenhum novo consentimento para este vínculo já concluído |
| Gestor do parceiro demo | ACTIVE; concessão na Central e painel com AAL2 aberto | Carteira com1 contrato, equipe com7 pessoas/papéis e4 escalas sintéticas consultadas |
| Portaria do condomínio demo | Convite aceito, vínculo PENDING | Outro gestor ativo precisa aprovar; autoaprovação recusada com409 MEMBERSHIP_SELF_DECISION_DENIED |
| Morador do condomínio demo | Convite não criado | Confirmar explicitamente a unidade fictícia; revisão automática recusou a escolha A-101 por não constar no consentimento |

A recusa de autoaprovação é regra de segurança válida, não erro para remover. Não trocar ator no SQL, não aprovar diretamente na tabela e não conceder gestão a uma segunda conta sem autorização específica. A autorização geral de testes não deve ser repetida como se os vínculos de gestão/parceiro ainda estivessem pendentes.

O Log de Segurança e as permanências tinham controllers e regras de domínio, mas faltavam os caminhos autenticados em SecurityConfiguration. A consulta real pela web recebia403 antes do serviço. PR #176 inclui os caminhos/métodos exatos e sete testes HTTP (identidade/parâmetros, CSRF/Bearer, MFA/negações, anônimos, métodos vizinhos e flags desligadas); não amplia papéis nem altera RLS. CI 41369953058 no head ba80806 e revisões concluídos. Publicação da API 41371039046 e worker 41371155348, fonte b1d4598273cb14c3f37834de8a779e47424a15ae. Conferência publicada: log consultado, cadeia íntegra com28 eventos e duas leituras SENSITIVE_VIEWED registradas; seção residencial de permanências vazia carregada com vínculo pré-existente na outra HML. A conta também é MANAGER local, portanto esse ensaio não comprova conta exclusivamente de parceiro. Entrada/alerta/saída operacional permanecem não executados. Recibo: [demo-access-hml-20261101.json](../evidence/demo-access-hml-20261101.json).

## Perfil específico

O workflow `.github/workflows/runtime-flags.yml` oferece `profile=demo` como padrão da interface. Esse perfil escreve somente `CP_DEMO_ENABLED=true` em Production do projeto Vercel CondPulse, que aponta à HML. Não altera facial, credenciais de acesso, financeiro ou chave de retirada e não gera segredo. O perfil histórico `access` continua explícito e separado.

O ACK inclui o perfil e o SHA: uma inspeção de access não autoriza demo. O estado precisa continuar igual entre inspect/apply; leitura de volta comprova a alteração. Projeto/time/repositório e branch principal continuam fixos. Perfil desconhecido, tipo sensível para a flag pública, ACK incorreto e estado alterado recusam antes da escrita. O CLI sem CP_FLAGS_PROFILE conserva access para compatibilidade dos chamadores antigos; o workflow sempre informa o perfil escolhido.

## Execução após revisão e CI

1. Aplicar/repetir a V055 pelo executor de banco, após conferir backup e janela exclusiva. Não marcar a confirmação de backup sem evidência.
2. Na branch principal, executar runtime-flags com operation=inspect, profile=demo e expected_commit igual ao SHA completo revisado. Não fornece ACK nem confirma alteração na inspeção.
3. Executar apply do mesmo perfil/SHA com o ACK retornado e alteração confirmada. A autorização de correções/carga da rodada está registrada em ESTADO_ATUAL; não pedir novamente escolhas de SMTP ou criação de segredos já feitos.
4. Executar Fly API inspect com a geração existente `a9735b66-b03f-4506-b2de-f4d72b1e1a36`. Exigir login READY/eligible, histórico/checksums válidos e CP_DEMO_ENABLED=true na configuração copiada. Se elegibilidade falhar, investigar; não criar outro login como atalho.
5. Fly API apply com o ACK correspondente, mesma geração e financeiro desmarcado. Storage já existe e sua chave é entregue pelo secret do Environment, nunca por chat. Com login READY, o apply apenas regrava flags/segredos previstos em stage; não roda migration nem muda a senha do login existente.
6. Nova inspeção e deploy com o deployAck atual, generation vazio. Conferir readiness, nova sessão e AAL2.
7. Criar o demo pela Central, com as permissões existentes do administrador. Conferir tenants/vínculos e jornadas pela aplicação. Não fabricar ator/capacidade no SQL e não confundir fixtures sintéticas com produção.

Registrar SHA, runs, flag lida de volta, IDs criados e resultado das jornadas. Preservar os dados até solicitação posterior de limpeza. Backup, V055, configuração, publicação, AAL2 e criação da geração1 já foram conferidos. O responsável concluiu o login/verificação humana; CAPTCHA não é bloqueio atual. Gestão e gestor do parceiro da conta atual foram vinculados depois por consentimento específico; portaria continua PENDING por recusa de autoaprovação e morador aguarda a unidade explicitamente confirmada. Restam esses requisitos e as jornadas dos perfis sem acesso operacional. Não resetar a geração existente para repetir o procedimento.

## Verificação local do executor

`node --test tests/runtime-flags.test.mjs`: seis testes passaram, incluindo alteração restrita ao demo, preview preservado, nenhum gerador de segredo chamado, recusa de ACK de outro perfil, repetição sem escrita e estado obsoleto recusado. Esses testes não comprovam apply remoto.
