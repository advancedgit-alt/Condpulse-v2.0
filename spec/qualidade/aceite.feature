# language: pt
Funcionalidade: CondPulse — especificação de aceite
  # Este arquivo é especificação. Não contém step definitions e não foi executado.

  @AC-001 @core
  Cenário: Morador tenta unidade alheia
    Dado que conta A possui vínculo somente com unidade A
    Quando consulta unidade B por identificador conhecido
    Então retorna 404/403 sem vazar existência ou conteúdo

  @AC-002 @core
  Cenário: Tenant alternado no pool
    Dado que duas contas válidas em tenants diferentes compartilham pool
    Quando requisições intercaladas reutilizam conexões
    Então cada transação usa seu contexto e nenhuma herda o anterior

  @AC-003 @core
  Cenário: Vínculo revogado com token válido
    Dado que JWT ainda está dentro de expiração
    Quando gestor revoga vínculo e usuário tenta gravar
    Então vínculo de negócio é revalidado e operação é negada

  @AC-004 @core
  Cenário: Operador sem MFA
    Dado que operador autentica com AAL1
    Quando tenta aprovar cadastro facial
    Então recebe exigência de MFA e nenhuma alteração ocorre

  @AC-005 @core
  Cenário: Sessão após logout
    Dado que cookie web já foi encerrado no servidor
    Quando browser repete ação com cookie antigo
    Então não há nova ação nem renovação da sessão

  @AC-006 @core
  Cenário: Ataque CSRF
    Dado que cookie válido e origem externa
    Quando POST mutável chega sem token CSRF válido
    Então BFF rejeita antes de executar regra de negócio

  @AC-007 @core
  Cenário: Recuperação de conta
    Dado que usuário perdeu acesso ao segundo fator
    Quando aciona suporte
    Então identidade é verificada, acesso é auditado e MFA não é desabilitado informalmente

  @AC-008 @core
  Cenário: Suporte expirado
    Dado que concessão temporária expirou
    Quando suporte tenta ler dados do cliente
    Então consulta é negada e tentativa auditada

  @AC-009 @core
  Cenário: Dupla reserva simultânea
    Dado que mesma área e intervalo livre
    Quando duas transações confirmam ao mesmo tempo
    Então apenas uma ocupa o slot; outra recebe conflito

  @AC-010 @core
  Cenário: Intervalo de limpeza
    Dado que reserva existente tem buffer de limpeza
    Quando nova reserva sobrepõe só o buffer
    Então constraint impede o conflito operacional

  @AC-011 @core
  Cenário: Repetição idempotente
    Dado que mesmo usuário e mesma chave com mesmo payload
    Quando request é reenviado após perda da resposta
    Então retorna mesmo resultado sem nova reserva

  @AC-012 @core
  Cenário: Chave reaproveitada indevidamente
    Dado que chave já foi utilizada
    Quando cliente repete chave com outro período
    Então retorna conflito de idempotência

  @AC-013 @core
  Cenário: Aprovação vencida
    Dado que pedido ultrapassou prazo máximo de análise
    Quando gestor tenta aprovar
    Então pedido expira ou exige nova disponibilidade; não ignora conflito

  @AC-014 @core
  Cenário: Mudança de regra
    Dado que regulamento foi alterado após pedido
    Quando usuário consulta sua reserva
    Então versão aceita continua rastreável e mudanças exigidas são explícitas

  @AC-015 @core
  Cenário: Cancelamento com convidados
    Dado que reserva possui convites ativos
    Quando reserva é cancelada
    Então convites ficam revogados e integração recebe estado desejado final

  @AC-016 @core
  Cenário: Fila de espera concorrente
    Dado que duas pessoas disputam vaga liberada
    Quando worker oferece e cliente confirma
    Então oferta segue ordem e apenas uma ocupa a vaga

  @AC-017 @core
  Cenário: Oferta expirada
    Dado que oferta de espera venceu
    Quando destinatário tenta aceitar
    Então nova reserva não é criada e fila pode seguir ao próximo

  @AC-018 @core
  Cenário: Fuso de horário
    Dado que celular está em fuso diferente
    Quando consulta horários de área em São Paulo
    Então apresenta fuso da área com período correto

  @AC-019 @core
  Cenário: Convite expirado
    Dado que convite fora de validade
    Quando portaria consulta token
    Então não libera visita e informa expiração

  @AC-020 @core
  Cenário: Revogação imediata lógica
    Dado que convite é revogado
    Quando portaria tenta decisão usando tela antiga
    Então backend rejeita com estado atualizado

  @AC-021 @core
  Cenário: Sem resposta do morador
    Dado que visita aguarda autorização e push foi aceito
    Quando prazo termina sem decisão
    Então não há autorização automática; segue procedimento de portaria

  @AC-022 @core
  Cenário: Duas respostas contraditórias
    Dado que pedido aguarda decisão
    Quando duas pessoas autorizadas aprovam/negam simultaneamente
    Então uma transição é aceita e outra recebe estado final

  @AC-023 @core
  Cenário: Prestador fora de janela
    Dado que prestador autorizado às terças 08–12
    Quando chega quarta-feira às 09
    Então autorização não vale para essa chegada

  @AC-024 @core
  Cenário: Link vazado
    Dado que pessoa possui apenas link de convite
    Quando tenta buscar dados de outros moradores
    Então token dá somente escopo mínimo e não cria sessão autenticada ampla

  @AC-025 @core
  Cenário: Entrega duplicada
    Dado que uma encomenda ainda está pendente
    Quando duas portarias registram retirada
    Então apenas um handoff é gravado

  @AC-026 @core
  Cenário: Delegação trocada
    Dado que delegação corresponde à encomenda A
    Quando é usada para retirar B
    Então operação rejeitada mesmo dentro do mesmo tenant

  @AC-027 @core
  Cenário: Código consumido
    Dado que código já foi usado
    Quando segunda retirada usa o código
    Então não confirma nova entrega

  @AC-028 @core
  Cenário: Conferência humana
    Dado que foto de etiqueta foi enviada
    Quando usuário abre detalhe
    Então imagem só aparece no escopo permitido e não comprova entrega sozinha

  @AC-029 @core
  Cenário: Aviso não publicado
    Dado que aviso é rascunho
    Quando morador busca seu ID
    Então não acessa conteúdo

  @AC-030 @core
  Cenário: Notificação sem entrega
    Dado que Expo aceita mas dispositivo está offline
    Quando worker recebe receipt de provedor
    Então mantém sem confirmação de leitura e registra apenas aceitação

  @AC-031 @core
  Cenário: Troca de conta no aparelho
    Dado que aparelho tinha usuário A
    Quando A sai e B entra
    Então tokens/caches antigos não entregam conteúdo de A para B

  @AC-032 @core
  Cenário: Objeto de outro condomínio
    Dado que usuário sabe caminho de arquivo alheio
    Quando pede URL assinada
    Então API nega e bucket não possui leitura pública

  @AC-033 @core
  Cenário: Arquivo disfarçado
    Dado que conteúdo executável tem extensão PDF
    Quando upload chega para conclusão
    Então permanece em quarentena/rejeitado conforme verificação

  @AC-034 @facial
  Cenário: Recebimento não é aplicação
    Dado que parceiro confirma só recebimento
    Quando app consulta estado
    Então mostra pendente de aplicação, não ativo

  @AC-035 @facial
  Cenário: Aplicação parcial
    Dado que credencial aceita em um de dois leitores
    Quando usuário consulta acessos
    Então mostra cada destino e pendência do segundo

  @AC-036 @facial
  Cenário: Callback antigo após revogação
    Dado que versão 3 solicita revogar
    Quando chega callback de criação versão 2
    Então não reativa e agenda reconciliação se necessário

  @AC-037 @facial
  Cenário: Timeout desconhecido
    Dado que parceiro não garante idempotência
    Quando chamada termina sem resposta
    Então estado UNKNOWN e consulta/reconciliação antes de nova mutação

  @AC-038 @facial
  Cenário: Equipamento offline
    Dado que leitor está sem comunicação
    Quando morador solicita novo cadastro
    Então não afirma disponibilidade; aplica procedimento de contingência

  @AC-039 @facial
  Cenário: Revogação urgente offline
    Dado que credencial precisa ser bloqueada e leitor offline
    Quando gestor revoga
    Então pendência crítica visível e acionamento do procedimento local

  @AC-040 @facial
  Cenário: Abertura antiga
    Dado que comando de abertura venceu durante falha
    Quando rede retorna
    Então comando não é executado nem repetido

  @AC-041 @facial
  Cenário: Webhook repetido
    Dado que mesmo evento externo já foi processado
    Quando provedor reenvia
    Então ack seguro sem duplicar histórico

  @AC-042 @facial
  Cenário: Assinatura inválida
    Dado que callback não possui autenticidade válida
    Quando endpoint recebe payload
    Então rejeita sem persistir evento de negócio

  @AC-043 @facial
  Cenário: Conector cruza tenant
    Dado que credencial pertence ao conector A
    Quando payload declara tenant B
    Então escopo deriva da credencial validada, nunca do campo arbitrário

  @AC-044 @facial
  Cenário: Relé sem passagem
    Dado que controladora envia apenas acionamento
    Quando histórico é exibido
    Então texto informa acionamento, não entrada de pessoa

  @AC-045 @facial
  Cenário: Saída não identificada
    Dado que instalação usa botoeira sem identificação
    Quando morador consulta visitante
    Então mostra sem registro de saída, não infere permanência

  @AC-046 @facial
  Cenário: Retorno de energia
    Dado que controladora reinicia
    Quando equipamento volta
    Então executa reconciliação e não envia abertura histórica

  @AC-047 @facial
  Cenário: Remoção de foto
    Dado que cadastro foi finalizado e prazo de retenção venceu
    Quando job executa purge
    Então foto transitória é removida e resultado auditado sem guardar a própria foto

  @AC-048 @facial
  Cenário: Recusa de biometria
    Dado que titular não usará reconhecimento facial
    Quando solicita acesso
    Então alternativa operacional documentada permanece disponível

  @AC-049 @core
  Cenário: Worker cai após efeito
    Dado que efeito idempotente já aconteceu
    Quando worker reinicia antes do ack
    Então reprocessamento não duplica o efeito

  @AC-050 @core
  Cenário: Stream após revogação
    Dado que sessão SSE está aberta
    Quando vínculo do usuário é revogado
    Então stream deixa de entregar conteúdo e exige reautorização

  @AC-051 @core
  Cenário: Restore sem efeitos colaterais
    Dado que backup anterior tem tarefas pendentes
    Quando restore de teste inicia
    Então worker e integrações ficam desativados até reconciliação

  @AC-052 @core
  Cenário: Backup sem arquivos
    Dado que banco foi restaurado
    Quando QA verifica anexos
    Então faltas são detectadas; backup só do banco não recebe aceite completo

  @AC-053 @core
  Cenário: Lote repetido
    Dado que importação já foi aplicada
    Quando mesmo lote é reenviado
    Então não duplica unidades nem reservas

  @AC-054 @core
  Cenário: Dados conflitantes
    Dado que duas reservas importadas se sobrepõem
    Quando preview é gerado
    Então exibe conflito e impede aplicação cega

  @AC-055 @core
  Cenário: Duas agendas
    Dado que sistema antigo e CondPulse estão disponíveis
    Quando cutover ocorre
    Então somente uma fonte aceita novas reservas por processo

  @AC-056 @core
  Cenário: Cobrança em atraso
    Dado que condomínio atrasa assinatura
    Quando processo comercial marca atraso
    Então não bloqueia automaticamente passagem física de moradores

  @AC-057 @core
  Cenário: Fonte ampliada
    Dado que morador utiliza escala de fonte grande
    Quando conclui convite e reserva
    Então campos e botões permanecem legíveis e alcançáveis

  @AC-058 @core
  Cenário: Rede interrompida na confirmação
    Dado que resposta se perde após gravação
    Quando usuário retorna à tela
    Então consulta estado pelo identificador e evita falsa falha ou duplicação

  @AC-059 @core
  Cenário: Navegação por teclado
    Dado que porteiro não usa mouse
    Quando consulta e registra encomenda
    Então foco visível e sequência lógica permitem conclusão

  @AC-060 @core
  Cenário: Escopo não homologado
    Dado que facial não recebeu aceite físico
    Quando release social é aprovado
    Então feature física fica desligada e contrato não a promete como entregue
