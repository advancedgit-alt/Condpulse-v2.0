import type {
  Amenity, AmenityAvailability, CancelReservation, CreateReservation, CurrentIdentity, InvitationAccepted, InvitationFirstAccess, Membership,
  MfaStatus, MutationResult, PartnerContext, PartnerPanel, PartnerRole, RecoveryCompleted, RecoveryVerified, Reservation, ReservationPage,
  ReservationPolicy, Session, TenantModules, TotpEnrollment, Unit,
} from './types';
import { partnerCapabilities, partnerRoles, tenantModuleIds } from './types';
import { parseRetryAfterSeconds, remainingCooldown } from './cooldown';
import { instantMicros } from './instant';

/**
 * Rotas que guardam a espera do 429. O primeiro acesso por convite consome o mesmo balde de tentativas do login no
 * servidor, então as duas rotas compartilham a espera.
 */
const COOLDOWN_KEYS: Readonly<Record<string, string>> = {
  '/auth/login': '/auth/login', '/auth/invitation/accept': '/auth/login', '/auth/manager-invitation/accept': '/auth/login',
  '/auth/refresh': '/auth/refresh',
};
const messages: Record<string, string> = {
  AUTHENTICATION_REQUIRED: 'Entre novamente para continuar.',
  SESSION_EXPIRED: 'Sua sessão terminou. Entre novamente para continuar.',
  INVALID_SESSION_COOKIE: 'Entre novamente para continuar.',
  AUTH_CREDENTIALS_REJECTED: 'Não foi possível validar seu e-mail e sua senha.',
  INVALID_LOGIN_REQUEST: 'Informe seu e-mail e sua senha.',
  ACCOUNT_INACTIVE: 'Seu acesso precisa ser validado pela gestão.',
  RECOVERY_REQUEST_INVALID: 'Informe um e-mail válido.',
  RECOVERY_LINK_INVALID: 'Link de recuperação inválido, expirado ou já utilizado. Solicite um novo link.',
  RECOVERY_SESSION_EXPIRED: 'O prazo para definir a nova senha terminou. Solicite um novo link.',
  RECOVERY_IN_PROGRESS: 'A troca de senha já está em andamento. Aguarde alguns segundos.',
  RECOVERY_NOT_ALLOWED: 'A troca de senha por este link não foi permitida. Solicite um novo link ou procure a gestão.',
  RECOVERY_OUTCOME_UNKNOWN: 'Não foi possível confirmar a troca da senha. Tente entrar com a nova senha; se não funcionar, solicite um novo link.',
  RECOVERY_ATTEMPTS_EXHAUSTED: 'Tentativas esgotadas para este link. Solicite um novo link.',
  RECOVERY_FACTOR_UNSUPPORTED: 'A recuperação pela web não está disponível para esta conta. Procure a gestão do condomínio.',
  RECOVERY_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir agora. Tente novamente em instantes.',
  RECOVERY_LINK_UNCERTAIN: 'Não foi possível confirmar a validação do link. Solicite um novo link.',
  PASSWORD_INVALID: 'Use de 12 a 72 caracteres (letras acentuadas contam em dobro), com pelo menos 4 caracteres diferentes.',
  PASSWORD_REJECTED: 'A senha não foi aceita. Use uma senha diferente da anterior e mais difícil de adivinhar.',
  MFA_CODE_REQUIRED: 'Informe o código de 6 dígitos do aplicativo autenticador.',
  AUTH_PROVIDER_UNAVAILABLE: 'O acesso está temporariamente indisponível. Tente novamente mais tarde.',
  AUTH_RATE_LIMITED: 'Aguarde um pouco antes de tentar novamente.',
  AUTH_RATE_LIMIT_UNAVAILABLE: 'Não foi possível verificar o acesso agora. Tente novamente mais tarde.',
  SESSION_REFRESH_REQUIRED: 'Sua sessão precisa ser renovada. Tente novamente.',
  SESSION_REFRESH_IN_PROGRESS: 'A sessão está sendo renovada. Aguarde antes de tentar novamente.',
  CSRF_ORIGIN_REJECTED: 'Não foi possível validar esta operação. Reabra o sistema e tente novamente.',
  ACTION_FORBIDDEN: 'Você não tem permissão para esta operação.',
  TENANT_ACCESS_DENIED: 'Este condomínio não está disponível para seu acesso.',
  UNIT_ACCESS_DENIED: 'Esta unidade não está disponível para esta operação.',
  RESERVATION_ACCESS_DENIED: 'Seu vínculo não permite acessar as reservas.',
  RESERVATION_RESOURCE_UNAVAILABLE: 'A reserva ou a área não está disponível para esta operação.',
  AMENITY_POLICY_NOT_CONFIGURED: 'As regras desta área ainda precisam ser configuradas.',
  AMENITY_POLICY_CHANGED: 'As regras da área mudaram. Atualize e revise as condições antes de reservar.',
  RESERVATION_CONFLICT: 'O período foi ocupado. Atualize os dados e escolha outro horário.',
  WAITLIST_SLOT_AVAILABLE: 'O período está livre. Faça a reserva diretamente.',
  WAITLIST_OWN_RESERVATION: 'A unidade já tem reserva ou pedido neste período.',
  WAITLIST_ALREADY_JOINED: 'A unidade já está na fila deste período.',
  WAITLIST_LIMIT_REACHED: 'A unidade já está em 5 filas de espera abertas.',
  WAITLIST_NOT_FOUND: 'Entrada da fila não encontrada.',
  WAITLIST_CHANGED: 'A fila de espera mudou. Atualize antes de continuar.',
  WAITLIST_OFFER_EXPIRED: 'O prazo da oferta terminou e a vaga seguiu para o próximo da fila.',
  WAITLIST_CLOSED: 'Esta entrada da fila já foi encerrada.',
  WAITLIST_AUTHOR_REQUIRED: 'Somente quem entrou na fila pode alterar esta entrada.',
  DELEGATION_NAME_INVALID: 'Informe o nome de quem vai retirar, com 2 a 160 caracteres.',
  DELEGATION_DOCUMENT_INVALID: 'Informe só os 2 a 4 últimos caracteres do documento.',
  DELEGATION_VALIDITY_INVALID: 'A autorização vale de 15 minutos a 7 dias.',
  DELEGATION_NOT_ACTIVE: 'Não há autorização em vigor para esta encomenda.',
  DELEGATION_CHANGED: 'A autorização foi alterada. Atualize a encomenda.',
  DELEGATION_NOT_ALLOWED: 'Seu vínculo não permite autorizar ou registrar retirada desta encomenda.',
  DELEGATION_CODE_REQUIRED: 'Informe o código de retirada.',
  DELEGATION_CODE_INVALID: 'Código incorreto. Confira com a pessoa: na quinta tentativa errada a autorização é bloqueada.',
  DELEGATION_LOCKED: 'Código incorreto pela quinta vez: a autorização foi bloqueada. A unidade precisa gerar outra.',
  DELEGATION_EXPIRED: 'A autorização desta encomenda venceu. A unidade precisa gerar outra.',
  DELEGATION_UNAVAILABLE: 'A retirada por código ainda não está disponível neste condomínio.',
  DELEGATION_NOT_FOUND: 'Não há autorização de retirada em vigor para esta encomenda.',
  MOVE_RULES_NOT_CONFIGURED: 'A gestão ainda não publicou as regras de mudança. Fale com a gestão.',
  MOVE_RULES_CHANGED: 'As regras de mudança foram alteradas. Leia a versão atual antes de continuar.',
  MOVE_RULES_REQUIRED: 'Leia e aceite as regras de mudança do condomínio.',
  MOVE_WINDOW_OUTSIDE_RULES: 'O período não atende às regras de mudança: confira dias, horário, antecedência e duração.',
  MOVE_WINDOW_INVALID: 'Informe início e fim, com o fim depois do início.',
  MOVE_KIND_INVALID: 'Escolha mudança de entrada, de saída ou entrega de móveis.',
  MOVE_PROVIDER_INVALID: 'Informe o nome do responsável ou da transportadora, com 2 a 160 caracteres.',
  MOVE_PLATE_INVALID: 'Informe a placa com 5 a 8 letras e números.',
  MOVE_ITEMS_INVALID: 'Descreva os itens com até 500 caracteres.',
  MOVE_NOTE_REQUIRED: 'Informe o motivo.',
  MOVE_NOTE_INVALID: 'Use até 500 caracteres na nota.',
  MOVE_CHANGED: 'O pedido de mudança foi alterado. Atualize antes de continuar.',
  MOVE_CLOSED: 'Este pedido de mudança já foi decidido ou encerrado.',
  MOVE_DECISION_CLOSED: 'O período já começou: o pedido não pode mais ser decidido.',
  MOVE_CANCELLATION_CLOSED: 'O período já começou. Fale com a gestão para cancelar.',
  MOVE_NOT_FOUND: 'Pedido de mudança não encontrado.',
  MOVE_RULES_TEXT_INVALID: 'Descreva as regras com até 2000 caracteres.',
  MOVE_RULES_DAYS_INVALID: 'Escolha ao menos um dia da semana.',
  MOVE_RULES_TIME_INVALID: 'O fim do horário precisa ser depois do início.',
  MOVE_RULES_NOTICE_INVALID: 'A antecedência mínima vai de 0 a 720 horas.',
  MOVE_RULES_DURATION_INVALID: 'A duração máxima vai de 30 a 720 minutos.',
  MOVE_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  CREDENTIAL_CONNECTOR_REQUIRED: 'Escolha a integração de acesso.',
  CREDENTIAL_HOLDER_REQUIRED: 'Escolha o morador que recebe a credencial.',
  CREDENTIAL_KIND_INVALID: 'Escolha cartão ou QR.',
  CREDENTIAL_LABEL_INVALID: 'Descreva a credencial com até 80 caracteres, sem o número completo do cartão.',
  CREDENTIAL_CONNECTOR_UNAVAILABLE: 'A integração de acesso não está ativa neste condomínio.',
  CREDENTIAL_HOLDER_INVALID: 'O vínculo escolhido não é de morador ativo. Atualize a lista de pessoas.',
  CREDENTIAL_VERSION_REQUIRED: 'Atualize a credencial antes de revogar.',
  CREDENTIAL_REASON_REQUIRED: 'Informe o motivo da revogação, com até 300 caracteres.',
  CREDENTIAL_CHANGED: 'A credencial foi alterada. Atualize antes de continuar.',
  CREDENTIAL_ALREADY_REVOKED: 'Esta credencial já foi revogada.',
  CREDENTIAL_NOT_FOUND: 'Credencial não encontrada.',
  CREDENTIAL_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  // Implantação física do acesso (AC-024; V041 áreas e pontos, V042 equipamentos, canais, vínculos e aceite).
  // Concessões técnicas pelo painel master (AC-003, V037).
  TECHNICAL_GRANT_SELF: 'Ninguém concede papel técnico a si próprio pelo painel. Peça a outra conta da plataforma.',
  TECHNICAL_GRANT_ID_REQUIRED: 'Não foi possível preparar a concessão. Reabra o formulário.',
  TECHNICAL_GRANT_ACCOUNT_REQUIRED: 'Informe a conta que recebe a concessão.',
  TECHNICAL_GRANT_ACCOUNT_UNKNOWN: 'A conta informada não existe.',
  TECHNICAL_GRANT_ROLE_INVALID: 'Papel técnico inválido.',
  TECHNICAL_GRANT_SCOPES_INVALID: 'Escolha de 1 a 16 escopos permitidos para o papel, sem repetir.',
  TECHNICAL_GRANT_VALIDITY_INVALID: 'A validade está fora do limite permitido pela plataforma.',
  TECHNICAL_GRANT_VERSION_REQUIRED: 'Atualize a lista antes de revogar.',
  TECHNICAL_GRANT_STALE: 'A concessão mudou ou já foi revogada. Atualize e confira antes de repetir.',
  TECHNICAL_GRANT_ID_CONFLICT: 'Este identificador já foi usado para outra concessão. Reabra o formulário.',
  TECHNICAL_GRANT_FORBIDDEN: 'Operação não permitida para este acesso.',
  TECHNICAL_GRANT_INVALID: 'A concessão não atende às regras de validade e escopo.',
  TECHNICAL_GRANT_UNAVAILABLE: 'Não foi possível concluir agora. Atualize e confira antes de repetir.',
  TECHNICAL_SCOPE_REQUIRED: 'Sua concessão técnica neste condomínio não inclui esta operação.',
  ACCESS_MAP_FORBIDDEN: 'Operação não permitida para este acesso.',
  ACCESS_MAP_CODE_CONFLICT: 'Este código já está em uso neste condomínio.',
  ACCESS_MAP_REFERENCE_INVALID: 'Área, bloco ou área reservável inexistente neste condomínio. Atualize os dados.',
  ACCESS_MAP_INVALID: 'Alteração recusada pelas regras do mapa (hierarquia, estado ou comissionamento).',
  ACCESS_MAP_UNAVAILABLE: 'Não foi possível concluir agora. Atualize e confira antes de repetir.',
  ACCESS_MAP_STALE: 'O cadastro foi alterado. Atualize e refaça a alteração.',
  ACCESS_AREA_ID_CONFLICT: 'Este identificador já foi usado para outra área. Reabra o formulário.',
  ACCESS_AREA_NOT_FOUND: 'Área não encontrada neste condomínio.',
  ACCESS_AREA_CODE_INVALID: 'Código: letras maiúsculas, números, _ ou -, até 64 caracteres.',
  ACCESS_AREA_NAME_INVALID: 'Informe o nome com 1 a 160 caracteres.',
  ACCESS_AREA_KIND_INVALID: 'Escolha o tipo da área.',
  ACCESS_AREA_AMENITY_INVALID: 'Área reservável exige escolher a área de reserva (e só ela).',
  ACCESS_AREA_SUBTYPE_INVALID: 'Subtipo físico inválido.',
  ACCESS_AREA_STATUS_INVALID: 'Situação da área inválida.',
  ACCESS_AREA_PARENT_INVALID: 'A área não pode estar dentro de si mesma.',
  ACCESS_AREA_TIME_ZONE_INVALID: 'Fuso horário inválido.',
  ACCESS_AREA_ID_REQUIRED: 'Não foi possível preparar o cadastro da área. Reabra o formulário.',
  ACCESS_AREA_VERSION_REQUIRED: 'Atualize a área antes de salvar.',
  ACCESS_POINT_ID_REQUIRED: 'Não foi possível preparar o cadastro do ponto. Reabra o formulário.',
  ACCESS_POINT_VERSION_REQUIRED: 'Atualize o ponto antes de salvar.',
  ACCESS_AREA_PHYSICAL_INVALID:'Informe rota essencial e separação física.',
  ACCESS_POINT_ID_CONFLICT: 'Este identificador já foi usado para outro ponto. Reabra o formulário.',
  ACCESS_POINT_NOT_FOUND: 'Ponto não encontrado neste condomínio.',
  ACCESS_POINT_CODE_INVALID: 'Código: letras maiúsculas, números, _ ou -, até 64 caracteres.',
  ACCESS_POINT_NAME_INVALID: 'Informe o nome com 1 a 160 caracteres.',
  ACCESS_POINT_AREAS_INVALID: 'Escolha origem e destino diferentes.',
  ACCESS_POINT_KIND_INVALID: 'Escolha o mecanismo do ponto.',
  ACCESS_POINT_FLOW_INVALID: 'Sentido inválido.',
  ACCESS_POINT_EGRESS_INVALID: 'Classe de saída inválida.',
  ACCESS_POINT_REF_INVALID: 'Referência de projeto inválida (3 a 120 caracteres: letras, números, . _ : / -).',
  ACCESS_POINT_STATUS_INVALID: 'Situação do ponto inválida; comissionado só pelo aceite técnico.',
  INSTALLATION_FORBIDDEN: 'Operação não permitida para este acesso.',
  INSTALLATION_CONFLICT: 'Já existe cadastro ativo igual (canal nativo, atuador ou ponto).',
  INSTALLATION_REFERENCE_INVALID: 'Equipamento, canal, ponto ou combinação inexistente neste condomínio. Atualize os dados.',
  INSTALLATION_INVALID: 'Alteração recusada pelas regras da instalação (função do canal, assinaturas, testes ou estado).',
  INSTALLATION_UNAVAILABLE: 'Não foi possível concluir agora. Atualize e confira antes de repetir.',
  INSTALLATION_STALE: 'O cadastro foi alterado. Atualize e refaça a alteração.',
  INSTALLATION_VERSION_REQUIRED: 'Atualize os dados antes de continuar.',
  INSTALLATION_DEVICE_ID_REQUIRED: 'Informe o equipamento e o conector.',
  INSTALLATION_DEVICE_EXTERNAL_INVALID: 'Identificador do equipamento no fabricante inválido.',
  INSTALLATION_DEVICE_INVALID: 'Dados do equipamento inválidos.',
  INSTALLATION_DEVICE_STATUS_INVALID: 'Situação do equipamento inválida.',
  INSTALLATION_DEVICE_LABEL_INVALID: 'Informe o nome com 1 a 120 caracteres.',
  INSTALLATION_DEVICE_DIRECTION_INVALID: 'Sentido do equipamento inválido.',
  INSTALLATION_DEVICE_HARDWARE_INVALID: 'Revisão de hardware com até 64 caracteres.',
  INSTALLATION_DEVICE_ROLE_INVALID: 'Papel do equipamento inválido.',
  INSTALLATION_DEVICE_MODE_INVALID: 'Escolha gateway local ou serviço de nuvem.',
  INSTALLATION_DEVICE_ID_CONFLICT: 'Este identificador já foi usado para outro equipamento. Reabra o formulário.',
  INSTALLATION_DEVICE_NOT_FOUND: 'Equipamento não encontrado neste condomínio.',
  INSTALLATION_CHANNEL_ID_REQUIRED: 'Informe o canal e o equipamento.',
  INSTALLATION_CHANNEL_NATIVE_INVALID: 'Espaço e canal nativos inválidos.',
  INSTALLATION_CHANNEL_KIND_INVALID: 'Escolha a função do canal.',
  INSTALLATION_CHANNEL_STATUS_INVALID: 'Situação do canal inválida.',
  INSTALLATION_CHANNEL_INVALID: 'Informe função e situação válidas do canal.',
  INSTALLATION_CHANNEL_ID_CONFLICT: 'Este identificador já foi usado para outro canal. Reabra o formulário.',
  INSTALLATION_CHANNEL_NOT_FOUND: 'Canal não encontrado neste condomínio.',
  INSTALLATION_BINDING_REQUIRED: 'Informe ponto, atuador, controladora e combinação homologada.',
  INSTALLATION_BINDING_DIRECTION_INVALID: 'Cada leitor exige o sentido em relação ao ponto.',
  INSTALLATION_BINDING_EVIDENCE_INVALID: 'Referência da ligação inválida (3 a 120 caracteres: letras, números, . _ : / -).',
  INSTALLATION_BINDING_ID_CONFLICT: 'Este identificador já foi usado para outro vínculo. Reabra o formulário.',
  INSTALLATION_BINDING_NOT_FOUND: 'Vínculo não encontrado neste condomínio.',
  INSTALLATION_ACCEPTANCE_REQUIRED: 'Escolha o aceite de instalação.',
  INSTALLATION_ACCEPTANCE_ID_REQUIRED: 'Informe o identificador do aceite.',
  INSTALLATION_ACCEPTANCE_REF_INVALID: 'Planta, topologia, relatório, emergência e reversão são obrigatórios (3 a 120 caracteres).',
  INSTALLATION_ACCEPTANCE_HASH_INVALID: 'Hashes SHA-256 em hexadecimal minúsculo (64 caracteres).',
  INSTALLATION_ACCEPTANCE_SUITE_INVALID: 'Versão da suíte de ensaios inválida.',
  INSTALLATION_ACCEPTANCE_ID_CONFLICT: 'Este identificador já foi usado para outro aceite. Reabra o formulário.',
  INSTALLATION_ACCEPTANCE_NOT_FOUND: 'Aceite não encontrado neste condomínio.',
  INSTALLATION_RESULTS_INVALID: 'Informe testes críticos pendentes e reprovados (0 ou mais) e o relatório.',
  INSTALLATION_TRANSITION_INVALID: 'Mudança de situação do aceite inválida.',
  INSTALLATION_REASON_REQUIRED: 'Informe o motivo (3 a 300 caracteres).',
  WAITLIST_PRIORITY: 'Há fila de espera para este período: a vaga vai primeiro para quem aguarda. Entre na fila ou escolha outro horário.',
  UNIT_RESERVATION_QUOTA_REACHED: 'A unidade atingiu o limite de reservas previsto para esta área.',
  RESERVATION_CHANGED: 'A reserva foi alterada. Atualize os dados antes de continuar.',
  RESERVATION_CANCELLATION_CLOSED: 'O prazo para cancelar esta reserva terminou.',
  RESERVATION_AUTHOR_REQUIRED: 'Somente quem fez a reserva pode cancelá-la pelo aplicativo.',
  RESERVATION_RULE_SNAPSHOT_INVALID: 'As regras aceitas desta reserva precisam ser conferidas pela gestão.',
  RESERVATION_WINDOW_INVALID: 'O período informado não atende às regras da área.',
  RESERVATION_RANGE_INVALID: 'Informe um período de consulta de até 31 dias.',
  AMENITY_AVAILABILITY_UNAVAILABLE: 'Não foi possível consultar a ocupação da área agora. Atualize a disponibilidade antes de confirmar.',
  IDEMPOTENCY_KEY_REUSED: 'Esta solicitação já foi enviada com outros dados. Confira a agenda antes de iniciar outra.',
  IDEMPOTENCY_KEY_EXPIRED: 'O prazo para repetir esta solicitação terminou. Confira a agenda antes de iniciar outra.',
  REQUEST_IN_PROGRESS: 'A operação ainda está em processamento. Aguarde e confira a agenda.',
  DEPENDENCY_UNAVAILABLE: 'Não foi possível consultar o sistema agora. Tente novamente mais tarde.',
  RESERVATION_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação agora. Confira a agenda antes de repetir.',
  TENANT_CONFIGURATION_INVALID: 'A configuração do condomínio precisa ser conferida pela gestão.',
  // Segundo fator e gestão local: textos próprios do cliente, sem repetir o corpo do servidor.
  MFA_REQUIRED: 'Confirme o segundo fator de autenticação para continuar.',
  MFA_CODE_REJECTED: 'Código não aceito. Confira o aplicativo autenticador e digite o código atual.',
  MFA_FACTOR_NOT_FOUND: 'O autenticador não foi encontrado. Atualize a página e cadastre novamente.',
  MFA_OPERATION_REJECTED: 'A operação de segundo fator foi recusada. Entre novamente para continuar.',
  STAFF_ACCESS_DENIED: 'Esta ação não está disponível para o seu vínculo.',
  MANAGEMENT_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação agora. Atualize os dados antes de tentar novamente.',
  MANAGEMENT_INPUT_INVALID: 'Os dados informados não atendem às regras do cadastro.',
  AUDIT_REASON_INVALID: 'O motivo deve ter até 500 caracteres.',
  BLOCK_NAME_INVALID: 'Informe o nome do bloco com até 60 caracteres.',
  BLOCK_NAME_TAKEN: 'Já existe um bloco com este nome.',
  BLOCK_NOT_FOUND: 'Bloco não encontrado neste condomínio.',
  UNIT_LABEL_INVALID: 'Informe a identificação da unidade com até 40 caracteres.',
  UNIT_LABEL_TAKEN: 'Já existe uma unidade com esta identificação no bloco.',
  UNIT_NOT_FOUND: 'Unidade não encontrada neste condomínio.',
  UNIT_CHANGED: 'A unidade foi alterada. Atualize os dados antes de salvar.',
  UNIT_STATUS_INVALID: 'Situação da unidade inválida.',
  // Importação de unidades em staging (CP-016).
  ATTACHMENT_PURPOSE_INVALID: 'Anexo só pode ser enviado para um pedido.',
  ATTACHMENT_TYPE_INVALID: 'Envie foto JPEG ou PNG ou documento PDF.',
  ATTACHMENT_TOO_LARGE: 'Fotos até 10 MB e PDFs até 20 MB.',
  ATTACHMENT_NAME_INVALID: 'Renomeie o arquivo para até 120 caracteres, sem aspas.',
  ATTACHMENT_LIMIT_REACHED: 'Este pedido já tem o máximo de 10 anexos. Atualize e confira.',
  ATTACHMENT_NOT_FOUND: 'Arquivo não encontrado.',
  ATTACHMENT_NOT_OWNER: 'Somente quem enviou o arquivo conclui o envio.',
  ATTACHMENT_NOT_UPLOADED: 'O arquivo ainda não chegou ao armazenamento. Envie de novo.',
  ATTACHMENT_NOT_AVAILABLE: 'Este arquivo ainda não foi liberado.',
  ATTACHMENT_STORAGE_UNAVAILABLE: 'O armazenamento de arquivos não respondeu. Tente novamente.',
  ATTACHMENT_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir agora. Atualize e tente novamente.',
  IMPORT_FILE_EMPTY: 'O arquivo está vazio ou não tem linhas depois do cabeçalho.',
  IMPORT_FILE_TOO_LARGE: 'O arquivo passa de 256 KB. Divida em lotes menores.',
  IMPORT_TOO_MANY_ROWS: 'O arquivo passa de 2.000 linhas. Divida em lotes menores.',
  IMPORT_HEADER_INVALID: 'Cabeçalho inválido. Use as colunas bloco, unidade e codigo_externo (unidade é obrigatória), sem repetir.',
  IMPORT_FILE_NAME_INVALID: 'O nome do arquivo deve ter até 200 caracteres.',
  IMPORT_VERSION_REQUIRED: 'Atualize a prévia antes de decidir.',
  IMPORT_BATCH_NOT_FOUND: 'Lote não encontrado neste condomínio.',
  IMPORT_BATCH_CHANGED: 'O lote ou o cadastro mudou. Atualize a prévia antes de decidir.',
  IMPORT_BATCH_DISCARDED: 'Este lote foi descartado. Envie o arquivo de novo para uma nova prévia.',
  IMPORT_BATCH_APPROVED: 'Este lote já foi aprovado e não pode ser descartado.',
  IMPORT_PARTIAL_NOT_ACCEPTED: 'O lote tem linhas com erro ou repetidas. Corrija o arquivo ou confirme que aprova só as linhas válidas.',
  IMPORT_BATCH_RACE: 'Outra pessoa da gestão alterou o cadastro ao mesmo tempo. Atualize e tente novamente.',
  IMPORT_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a importação agora. Atualize a prévia antes de tentar novamente.',
  UNIT_VERSION_REQUIRED: 'Atualize os dados da unidade antes de salvar.',
  AMENITY_NAME_INVALID: 'Informe o nome da área com até 120 caracteres.',
  AMENITY_NAME_TAKEN: 'Já existe uma área com este nome.',
  AMENITY_CAPACITY_INVALID: 'A capacidade deve estar entre 1 e 10000 pessoas.',
  AMENITY_FEE_INVALID: 'A taxa informativa deve estar entre R$ 0,00 e R$ 100.000,00.',
  AMENITY_BUFFER_INVALID: 'O intervalo de limpeza deve ter entre 0 e 1440 minutos.',
  AMENITY_APPROVAL_REQUIRED: 'Informe se a área exige aprovação da gestão.',
  AMENITY_ACTIVE_REQUIRED: 'Informe se a área está ativa.',
  AMENITY_POLICY_REQUIRED: 'Configure as regras de reserva da área.',
  AMENITY_POLICY_INVALID: 'As regras informadas estão fora dos limites permitidos.',
  AMENITY_INPUT_INVALID: 'Os dados informados não atendem às regras da área.',
  AMENITY_NOT_FOUND: 'Área não encontrada neste condomínio.',
  AMENITY_CHANGED: 'A área foi alterada. Atualize os dados antes de salvar.',
  AMENITY_VERSION_REQUIRED: 'Atualize os dados da área antes de salvar.',
  AMENITY_INACTIVE: 'A área está inativa.',
  AMENITY_BLOCK_CONFLICT: 'Há reservas ou pedidos no período. Decida ou cancele cada um com motivo antes de bloquear.',
  AMENITY_BLOCK_WINDOW_INVALID: 'Informe início a partir de agora e fim posterior, em período de até 366 dias.',
  MEMBERSHIP_NOT_FOUND: 'Vínculo não encontrado neste condomínio.',
  MEMBERSHIP_CHANGED: 'O vínculo foi alterado. Atualize os dados antes de decidir.',
  MEMBERSHIP_REASON_REQUIRED: 'Informe o motivo.',
  MEMBERSHIP_SELF_REVOCATION_DENIED: 'Outro gestor precisa revogar o seu vínculo de gestão.',
  MEMBERSHIP_SELF_DECISION_DENIED: 'Outro gestor precisa decidir sobre o seu próprio vínculo.',
  MEMBERSHIP_DECISION_INVALID: 'Escolha aprovar ou rejeitar o vínculo.',
  MEMBERSHIP_STATUS_INVALID: 'Situação de vínculo inválida.',
  MEMBERSHIP_VERSION_REQUIRED: 'Atualize os dados do vínculo antes de decidir.',
  MEMBERSHIP_ALREADY_EXISTS: 'Você já possui este vínculo no condomínio.',
  LAST_MANAGER_REQUIRED: 'O condomínio precisa manter ao menos um gestor ativo.',
  INVITATION_EMAIL_INVALID: 'Informe um e-mail válido.',
  INVITATION_INPUT_INVALID: 'Os dados do convite não atendem às regras.',
  INVITATION_NAME_INVALID: 'Informe o nome com até 160 caracteres.',
  INVITATION_ROLE_INVALID: 'Papel não disponível para convite.',
  INVITATION_UNIT_INVALID: 'Equipe não é vinculada a unidade.',
  INVITATION_UNIT_REQUIRED: 'Informe a unidade do convite.',
  INVITATION_VALIDITY_INVALID: 'A validade deve ter entre 1 e 14 dias.',
  INVITATION_NOT_PENDING: 'O convite não está mais pendente.',
  INVITATION_NOT_AVAILABLE: 'Convite inválido, expirado, já utilizado ou emitido para outro e-mail.',
  INVITATION_FIRST_ACCESS_UNKNOWN: 'Não foi possível confirmar o aceite do convite. Tente entrar com seu e-mail e senha; se o convite tiver sido aceito, o pedido aparecerá para a gestão.',
  INVITATION_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir o convite. Tente novamente.',
  RENTAL_ALREADY_ACTIVE: 'A unidade já está marcada como alugada.',
  RENTAL_NOT_ACTIVE: 'A unidade não está marcada como alugada.',
  RENTAL_OWNER_REQUIRED: 'Cadastre o vínculo de proprietário da unidade antes de marcar a locação.',
  RENTAL_OWNER_IS_OCCUPANT: 'O proprietário ainda tem vínculo de morador nesta unidade. Revogue-o antes de marcar a locação.',
  RENTAL_REASON_REQUIRED: 'Informe o motivo.',
  RENTAL_CONFLICT: 'Os vínculos da unidade mudaram. Atualize os dados e tente novamente.',
  RENTAL_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  MEMBERSHIP_RENTAL_CONFLICT: 'Proprietário de unidade alugada não pode ter vínculo de morador nela.',
  RESERVATION_NOT_FOUND: 'Reserva não encontrada neste condomínio.',
  RESERVATION_DECISION_EXPIRED: 'O prazo de decisão terminou. O pedido expirou e a unidade precisa solicitar novamente.',
  RESERVATION_DECISION_INVALID: 'Escolha aprovar ou rejeitar o pedido.',
  RESERVATION_REQUEST_INVALID: 'Revise os dados da reserva.',
  IDEMPOTENCY_KEY_INVALID: 'Não foi possível identificar esta operação. Recarregue a página e tente novamente.',
  RESERVATION_SELF_DECISION_DENIED: 'Você não pode decidir o próprio pedido. Outra pessoa da gestão precisa decidir ou o pedido pode ser retirado.',
  RESERVATION_REASON_REQUIRED: 'Informe o motivo.',
  RESERVATION_REASON_INVALID: 'O motivo deve ter até 500 caracteres.',
  RESERVATION_REQUESTER_INACTIVE: 'O solicitante não tem mais vínculo ativo para reservar por esta unidade.',
  RESERVATION_UNIT_INACTIVE: 'A unidade do pedido está inativa. Rejeite o pedido informando o motivo.',
  RESERVATION_STATUS_INVALID: 'Situação de reserva inválida.',
  RESERVATION_VERSION_REQUIRED: 'Atualize os dados da reserva antes de decidir.',
  RESERVATION_INPUT_INVALID: 'Os dados informados não atendem às regras.',
  AUDIT_CURSOR_INVALID: 'A página da auditoria expirou. Recarregue a lista.',
  AUDIT_LIMIT_INVALID: 'Use um limite entre 1 e 100.',
  AUDIT_DEPENDENCY_UNAVAILABLE: 'Não foi possível consultar a auditoria. Tente novamente.',
  PLATFORM_ACCESS_DENIED: 'Esta área é exclusiva da administração da plataforma.',
  ACCESS_MODEL_NOT_FOUND: 'Perfil do catálogo não encontrado.',
  ACCESS_MODEL_FILTER_INVALID: 'Fabricante inválido no filtro do catálogo.',
  HOMOLOGATION_NOT_FOUND: 'Homologação não encontrada.',
  HOMOLOGATION_FORBIDDEN: 'Operação não permitida para este acesso.',
  HOMOLOGATION_UNAVAILABLE: 'Não foi possível consultar ou registrar a homologação agora. Tente novamente.',
  HOMOLOGATION_ID_CONFLICT: 'Este identificador já foi usado para outro dossiê. Descarte e abra um novo formulário.',
  HOMOLOGATION_DECISION_ID_CONFLICT: 'Este identificador já foi usado para outra decisão. Descarte e abra uma nova decisão.',
  HOMOLOGATION_STALE: 'A homologação mudou depois da leitura. Atualize e confira antes de decidir.',
  HOMOLOGATION_CONFLICT: 'A homologação foi alterada ao mesmo tempo. Atualize e tente de novo.',
  HOMOLOGATION_TRANSITION_INVALID: 'A decisão não segue as regras (transição, evidência ou capacidade observada).',
  HOMOLOGATION_EVIDENCE_REQUIRED: 'Aprovação exige ao menos uma referência de evidência de execução própria.',
  HOMOLOGATION_EVIDENCE_INVALID: 'Até 300 referências de evidência (identificadores), sem repetição.',
  HOMOLOGATION_CAPABILITIES_INVALID: 'Capacidades inválidas: aprovação só lista capacidades observadas no dossiê.',
  HOMOLOGATION_REVIEW_INVALID: 'A revisão precisa ser futura e só vale em aprovação.',
  HOMOLOGATION_RESTRICTIONS_INVALID: 'Restrições de 1 a 500 caracteres, até 100, só em aprovação.',
  HOMOLOGATION_DECISION_INVALID: 'Decisão inválida.',
  HOMOLOGATION_VERSION_REQUIRED: 'Atualize a homologação antes de decidir.',
  HOMOLOGATION_FIRMWARE_INVALID: 'Versão de firmware inválida (exata, até 128 caracteres).',
  HOMOLOGATION_ADAPTER_INVALID: 'Nome ou versão do adaptador inválidos.',
  HOMOLOGATION_CONTRACT_INVALID: 'Versão do contrato da API inválida.',
  HOMOLOGATION_MODE_INVALID: 'Modo de operação inválido.',
  HOMOLOGATION_TOPOLOGY_INVALID: 'Topologia inválida.',
  HOMOLOGATION_LIMITS_INVALID: 'Limites inválidos: números inteiros não negativos ou em branco.',
  HOMOLOGATION_LIMITATIONS_INVALID: 'Limitações de 1 a 500 caracteres, até 100.',
  TENANT_MODULE_CHANGED: 'O módulo foi alterado por outra pessoa depois da leitura. Recarregue e confira antes de repetir.',
  TENANT_MODULE_INVALID: 'Os dados da alteração do módulo não atendem às regras.',
  TENANT_MODULE_UNAVAILABLE: 'Não foi possível consultar ou alterar os módulos agora. Tente novamente.',
  MODULE_DISABLED: 'Módulo desligado pela administradora para este condomínio. Nenhum dado foi apagado.',
  TENANT_MODULE_FORBIDDEN: 'Operação não permitida para este acesso.',
  TENANT_MODULE_STATE_REQUIRED: 'Informe se o módulo fica liberado ou suspenso.',
  TENANT_MODULE_VERSION_REQUIRED: 'Recarregue o módulo antes de alterar.',
  PLATFORM_INPUT_INVALID: 'Os dados informados não atendem às regras do cadastro.',
  PLATFORM_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Tente novamente.',
  PARCEL_UNIT_REQUIRED: 'Informe a unidade da encomenda.',
  PARCEL_QUANTITY_INVALID: 'Informe a quantidade de volumes, de 1 a 1000.',
  PARCEL_DESCRIPTION_INVALID: 'Use até 500 caracteres visíveis na descrição.',
  PARCEL_LOCATION_REQUIRED: 'Informe o local de guarda, com até 160 caracteres.',
  PARCEL_UNIT_UNAVAILABLE: 'Unidade não disponível para recebimento.',
  PARCEL_NOT_FOUND: 'Encomenda não encontrada.',
  PARCEL_VERSION_REQUIRED: 'Atualize a lista antes de registrar a entrega.',
  PARCEL_RECIPIENT_REQUIRED: 'Escolha a pessoa que retira a encomenda.',
  PARCEL_RECIPIENT_NOT_ALLOWED: 'A pessoa escolhida não pode retirar encomendas desta unidade. A lista de pessoas foi atualizada.',
  PARCEL_VOIDED: 'Este registro de encomenda foi anulado e não está mais para retirada.',
  PARCEL_DUPLICATE_SUSPECTED: 'Há uma encomenda igual registrada para esta unidade nos últimos 10 minutos. Confira antes de registrar outra.',
  PARCEL_VOID_REASON_REQUIRED: 'Informe o motivo da anulação, com até 500 caracteres.',
  PARCEL_IDENTITY_CHECK_REQUIRED: 'Confirme que conferiu o documento da pessoa que retira.',
  PARCEL_ALREADY_DELIVERED: 'Esta encomenda já foi retirada.',
  PARCEL_CHANGED: 'A encomenda foi alterada. Atualize a lista.',
  PARCEL_STATUS_INVALID: 'Situação de encomenda inválida.',
  PARCEL_FILTER_INVALID: 'Revise o filtro informado.',
  PARCEL_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  VISIT_INPUT_INVALID: 'Informe os dados da visita.',
  VISIT_PURPOSE_INVALID: 'Use até 200 caracteres na finalidade.',
  VISIT_PERIOD_INVALID: 'O convite começa a partir de agora (até 90 dias à frente), termina depois do início e vale por até 7 dias.',
  VISIT_PERMIT_NOT_FOUND: 'Convite não encontrado.',
  VISIT_RULE_NOT_FOUND: 'Regra não encontrada.',
  VISIT_RULE_CHANGED: 'A regra foi alterada. Atualize antes de continuar.',
  VISIT_RULE_REVOKED: 'Esta regra já foi revogada.',
  VISIT_RULE_DAYS_INVALID: 'Escolha de 1 a 7 dias da semana, sem repetir.',
  VISIT_RULE_TIME_INVALID: 'Informe o horário com fim depois do início no mesmo dia.',
  VISIT_RULE_PERIOD_INVALID: 'A regra começa hoje ou depois (até 90 dias à frente) e dura até 180 dias.',
  RESERVATION_GUEST_UNAVAILABLE: 'Reserva não disponível para convidados.',
  RESERVATION_NOT_CONFIRMED: 'Só reservas confirmadas e ainda não encerradas recebem convidados.',
  RESERVATION_GUEST_LIMIT: 'A reserva já tem o número máximo de convidados da área.',
  RESERVATION_GUEST_TOO_EARLY: 'Convidados podem ser incluídos a partir de 90 dias antes do evento.',
  VISIT_PERMIT_LINK_UNAVAILABLE: 'Só convites em vigor ou agendados podem ser compartilhados.',
  PERMIT_LINK_UNAVAILABLE: 'Este link de convite não está disponível. Peça um novo link a quem convidou.',
  PERMIT_LINK_DEPENDENCY_UNAVAILABLE: 'Não foi possível abrir o convite agora. Tente novamente.',
  VISIT_VERSION_REQUIRED: 'Atualize os convites antes de revogar.',
  VISIT_REVOKE_REASON_REQUIRED: 'Informe o motivo da revogação, com até 500 caracteres.',
  VISIT_PERMIT_REVOKED: 'Este convite foi revogado.',
  VISIT_PERMIT_EXPIRED: 'A vigência deste convite terminou.',
  VISIT_PERMIT_CHANGED: 'O convite foi alterado. Atualize os convites antes de continuar.',
  VISIT_FILTER_INVALID: 'Use até 80 caracteres visíveis na busca por nome.',
  VISIT_ACCESS_DENIED: 'Esta ação não está disponível para o seu vínculo.',
  VISIT_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  VISITOR_NAME_INVALID: 'Informe o nome do visitante, de 2 a 160 caracteres.',
  VISITOR_DOCUMENT_INVALID: 'Informe só o final do documento, de 2 a 4 letras ou números.',
  ARRIVAL_FILTER_INVALID: 'Situação de chegada inválida.',
  ARRIVAL_UNIT_REQUIRED: 'Informe a unidade de destino.',
  ARRIVAL_UNIT_UNAVAILABLE: 'Unidade não disponível para a chegada.',
  ARRIVAL_PERMIT_INVALID: 'O convite informado não confere com a unidade ou a pessoa. Confira a visita prevista.',
  VISIT_PERMIT_INACTIVE: 'Quem convidou não tem mais vínculo para convidar pela unidade: o convite não vale. Siga o procedimento de chegada sem convite.',
  ARRIVAL_NOT_FOUND: 'Chegada não encontrada.',
  ARRIVAL_DECISION_INVALID: 'Responda autorizando ou recusando a entrada.',
  ARRIVAL_SOURCE_INVALID: 'Informe se a confirmação foi pelo interfone ou presencial.',
  ARRIVAL_NOTE_REQUIRED: 'Anote quem confirmou e como, com até 300 caracteres.',
  ARRIVAL_CANCEL_REASON_REQUIRED: 'Informe o motivo do encerramento, com até 300 caracteres.',
  ARRIVAL_EXPIRED: 'O prazo para responder pelo aplicativo terminou. A portaria segue pelo interfone ou pelo procedimento do condomínio.',
  ARRIVAL_CANCELLED: 'Esta chegada foi encerrada pela portaria.',
  ARRIVAL_ALREADY_DECIDED: 'Esta chegada já foi respondida. Atualize a lista para ver a decisão.',
  ACCESS_BASIS_REQUIRED: 'Informe a chegada autorizada ou o convite em vigor.',
  ACCESS_EVIDENCE_INVALID: 'Informe como a pessoa foi conferida.',
  ACCESS_NOTE_INVALID: 'Use até 300 caracteres na observação.',
  ACCESS_CORRECTION_REASON_REQUIRED: 'Informe o motivo da correção, com até 300 caracteres.',
  ACCESS_ARRIVAL_NOT_AUTHORIZED: 'Esta chegada não tem autorização da unidade: nada é liberado.',
  ACCESS_AUTHORIZATION_EXPIRED: 'A autorização desta chegada não vale mais para entrada. Registre nova chegada.',
  ACCESS_UNIT_INACTIVE: 'A unidade desta chegada não está ativa: nada é liberado. Siga o procedimento do condomínio.',
  ACCESS_PERMIT_NOT_CURRENT: 'O convite não vale agora (antes do início, vencido, revogado ou de quem perdeu o vínculo) e não autoriza a entrada.',
  ACCESS_ENTRY_ALREADY_RECORDED: 'A entrada desta chegada já foi registrada. Atualize a fila.',
  ACCESS_EXIT_ALREADY_RECORDED: 'A saída desta entrada já foi registrada. Atualize a lista.',
  ACCESS_ENTRY_CORRECTED: 'Esta entrada foi corrigida e não recebe saída.',
  ACCESS_ALREADY_CORRECTED: 'Este registro já foi corrigido. Atualize a lista.',
  ACCESS_CORRECTION_INVALID: 'Uma correção não é corrigida. Corrija a entrada ou a saída.',
  ACCESS_STATE_CHANGED: 'A autorização ou o registro mudou. Atualize a lista antes de repetir.',
  ACCESS_EVENT_NOT_FOUND: 'Registro de acesso não encontrado.',
  ACCESS_FORBIDDEN: 'Esta ação não está disponível para o seu vínculo.',
  ACCESS_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  ANNOUNCEMENT_TITLE_INVALID: 'Informe o título do aviso, de 3 a 160 caracteres.',
  ANNOUNCEMENT_BODY_INVALID: 'Informe o texto do aviso, com até 5.000 caracteres e sem caracteres invisíveis.',
  ANNOUNCEMENT_AUDIENCE_INVALID: 'Escolha o público: condomínio inteiro, ou de 1 a 200 blocos ou unidades, sem repetição.',
  ANNOUNCEMENT_TARGETS_INVALID: 'Algum bloco ou unidade do público não existe neste condomínio. Atualize a lista.',
  ANNOUNCEMENT_VERSION_REQUIRED: 'Atualize os avisos antes de continuar.',
  ANNOUNCEMENT_REASON_INVALID: 'Use até 500 caracteres no motivo.',
  ANNOUNCEMENT_NOT_FOUND: 'Aviso não encontrado. Ele pode ter sido arquivado ou não ser dirigido a esta unidade.',
  ANNOUNCEMENT_NOT_DRAFT: 'Somente rascunho pode ser alterado ou publicado. Para corrigir um aviso publicado, arquive e publique outro.',
  ANNOUNCEMENT_CHANGED: 'O aviso foi alterado. Atualize a lista antes de continuar.',
  ANNOUNCEMENT_ARCHIVED: 'Este aviso já foi arquivado.',
  ANNOUNCEMENT_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  NOTIFICATION_NOT_FOUND: 'Aviso não encontrado. Ele pode ter deixado de valer para o seu vínculo.',
  SERVICE_REQUEST_INPUT_INVALID: 'Informe a categoria, o título e a descrição do pedido.',
  SERVICE_REQUEST_CATEGORY_INVALID: 'Escolha uma categoria válida.',
  SERVICE_REQUEST_TITLE_INVALID: 'Informe o título do pedido, de 3 a 120 caracteres.',
  SERVICE_REQUEST_DESCRIPTION_INVALID: 'Descreva o pedido com até 2.000 caracteres, sem caracteres invisíveis.',
  SERVICE_REQUEST_MESSAGE_INVALID: 'Escreva a mensagem com até 2.000 caracteres, sem caracteres invisíveis.',
  SERVICE_REQUEST_VISIBILITY_INVALID: 'Escolha se a mensagem é visível para a unidade ou comentário interno.',
  SERVICE_REQUEST_FILTER_INVALID: 'Filtro de andamento inválido.',
  SERVICE_REQUEST_VERSION_REQUIRED: 'Atualize o pedido antes de salvar.',
  SERVICE_REQUEST_STATE_INVALID: 'Escolha um andamento válido.',
  SERVICE_REQUEST_TRANSITION_INVALID: 'Este andamento não pode ir para o escolhido. Atualize o pedido.',
  SERVICE_REQUEST_RESOLUTION_REQUIRED: 'Informe a resolução para concluir o pedido.',
  SERVICE_REQUEST_RESOLUTION_INVALID: 'Use até 1.000 caracteres na resolução, sem caracteres invisíveis.',
  SERVICE_REQUEST_DUE_INVALID: 'O prazo comunicado vai de hoje até um ano.',
  SERVICE_REQUEST_ASSIGNEE_INVALID: 'Escolha como responsável alguém da equipe com vínculo vigente.',
  SERVICE_REQUEST_UNCHANGED: 'Nada mudou no pedido.',
  SERVICE_REQUEST_NOT_FOUND: 'Pedido não encontrado. Ele pode não ser desta unidade.',
  SERVICE_REQUEST_CLOSED: 'O pedido foi encerrado; abra um novo pedido se precisar.',
  SERVICE_REQUEST_CHANGED: 'O pedido foi alterado. Atualize antes de continuar.',
  SERVICE_REQUEST_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  NOTIFICATION_DEPENDENCY_UNAVAILABLE: 'Não foi possível consultar os avisos agora. Tente novamente.',
  TENANT_CHANGED: 'O condomínio foi alterado por outra pessoa. Recarregue os dados antes de salvar.',
  TENANT_NOT_FOUND: 'Condomínio não encontrado.',
  TENANT_NAME_INVALID: 'Informe o nome do condomínio com até 160 caracteres.',
  TENANT_STATE_INVALID: 'Informe se o condomínio fica ativo ou suspenso.',
  TENANT_VERSION_REQUIRED: 'Recarregue os dados do condomínio antes de salvar.',
  TENANT_INPUT_INVALID: 'Informe os dados do condomínio.',
  TENANT_TIME_ZONE_INVALID: 'Informe um fuso horário IANA válido, por exemplo America/Sao_Paulo.',
  TENANT_UNITS_INVALID: 'Informe de 1 a 250 unidades.',
  TENANT_UNITS_DUPLICATED: 'Há unidades repetidas na lista. Cada identificação deve aparecer uma única vez.',
  TENANT_CREATION_KEY_REUSED: 'Esta criação já foi enviada com outros dados. Atualize a lista e confira antes de iniciar outra.',
  // Condomínio demo (CPX-046, V053).
  DEMO_RESET_REAL_TENANT: 'Somente o condomínio demo pode ser resetado. Condomínios reais nunca são resetados.',
  DEMO_CHANGED: 'O condomínio demo mudou desde que a tela foi aberta. Atualize e confira antes de repetir.',
  DEMO_KEY_REUSED: 'Esta operação já foi enviada com outros dados. Atualize a tela antes de repetir.',
  DEMO_CONFIRMATION_REQUIRED: 'Digite DEMO para confirmar a operação no condomínio demo.',
  TENANT_CREATION_CONFLICT: 'Os dados conflitam com um cadastro existente. Atualize a lista e confira.',
  TENANT_SUSPENDED: 'Reative o condomínio antes de convidar o gestor.',
  TENANT_MANAGER_EXISTS: 'O condomínio já tem gestor vigente; novos vínculos seguem pela gestão local.',
  MANAGER_REQUIRED: 'Informe o primeiro gestor do condomínio.',
  MANAGER_NAME_INVALID: 'Informe o nome do gestor com até 160 caracteres visíveis.',
  MANAGER_EMAIL_INVALID: 'Informe um e-mail válido para o gestor.',
  MANAGER_INVITATION_VALIDITY_INVALID: 'A validade do convite deve ter entre 1 e 14 dias.',
  MANAGER_INVITATION_INPUT_INVALID: 'Informe os dados do convite.',
  MANAGER_INVITATION_NOT_PENDING: 'O convite não está mais pendente.',
  PLATFORM_CONFLICT: 'Os dados conflitam com um cadastro existente. Atualize e confira.',
  OCCURRENCE_INPUT_INVALID: 'Informe a categoria, o título e a descrição da ocorrência.',
  OCCURRENCE_CATEGORY_INVALID: 'Escolha uma categoria válida.',
  OCCURRENCE_TITLE_INVALID: 'Informe o título com 3 a 120 caracteres.',
  OCCURRENCE_DESCRIPTION_INVALID: 'Descreva a ocorrência com até 2.000 caracteres, sem caracteres invisíveis.',
  OCCURRENCE_UNIT_INVALID: 'Escolha uma unidade deste condomínio.',
  OCCURRENCE_ID_CONFLICT: 'Este registro já existe com outro conteúdo. Atualize a lista.',
  OCCURRENCE_VERSION_REQUIRED: 'Atualize a ocorrência antes de encerrar.',
  OCCURRENCE_RESOLUTION_INVALID: 'Descreva a resolução com até 1.000 caracteres.',
  OCCURRENCE_NOT_FOUND: 'Ocorrência não encontrada.',
  OCCURRENCE_RESOLVED: 'A ocorrência já foi encerrada. Atualize a lista.',
  OCCURRENCE_CHANGED: 'A ocorrência foi alterada. Atualize antes de encerrar.',
  OCCURRENCE_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação. Atualize e confira antes de repetir.',
  AUDIT_REASON_REQUIRED: 'Informe o motivo da alteração, com até 500 caracteres.',
  OUTBOX_STATE_INVALID: 'Escolha tarefas com falha ou ignoradas.',
  OUTBOX_TASK_NOT_FOUND: 'Tarefa não encontrada.',
  OUTBOX_TASK_NOT_REPLAYABLE: 'Só tarefa com falha ou ignorada volta para a fila. Atualize a lista.',
  BILLING_INPUT_INVALID: 'Confira os dados informados.',
  BILLING_PLAN_NAME_INVALID: 'Informe o nome do plano com até 120 caracteres.',
  BILLING_PRICE_INVALID: 'Informe o valor em reais, de R$ 0,01 até R$ 1.000.000,00.',
  BILLING_PERIOD_INVALID: 'Use período mensal ou anual.',
  BILLING_PLAN_NAME_TAKEN: 'Já existe um plano com este nome.',
  BILLING_PLAN_NOT_FOUND: 'Plano não encontrado.',
  BILLING_VERSION_REQUIRED: 'Recarregue os dados antes de salvar.',
  BILLING_PLAN_CHANGED: 'O plano foi alterado por outra pessoa. Recarregue os dados antes de salvar.',
  BILLING_PLAN_INACTIVE: 'O plano não está disponível para novas assinaturas.',
  BILLING_START_INVALID: 'Informe o início da assinatura entre os dias 1 e 28 do mês, a partir de 2000.',
  BILLING_START_OVERLAP: 'O início precisa ser depois do encerramento da assinatura anterior do condomínio.',
  BILLING_DUE_DAY_INVALID: 'Informe o dia de vencimento entre 1 e 28.',
  BILLING_SUBSCRIPTION_EXISTS: 'O condomínio já tem uma assinatura ativa.',
  BILLING_SUBSCRIPTION_NOT_FOUND: 'Assinatura não encontrada.',
  BILLING_SUBSCRIPTION_ENDED: 'A assinatura já está encerrada.',
  BILLING_SUBSCRIPTION_CHANGED: 'A assinatura foi alterada por outra pessoa. Recarregue os dados.',
  BILLING_END_INVALID: 'Informe um encerramento depois do início da assinatura.',
  BILLING_CHARGES_AFTER_END: 'Há cobrança de período depois desta data. Cancele-a ou escolha outra data.',
  BILLING_FILTER_INVALID: 'Situação de cobrança inválida.',
  BILLING_PERIOD_START_INVALID: 'O período precisa começar no mesmo dia do início da assinatura, a partir dele, e terminar até o encerramento.',
  BILLING_CHARGE_EXISTS: 'Já existe cobrança para este período.',
  BILLING_CHARGE_NOT_FOUND: 'Cobrança não encontrada.',
  BILLING_CHARGE_PAID: 'A cobrança está paga. Estorne o pagamento antes de cancelar.',
  BILLING_CHARGE_CANCELLED: 'A cobrança já está cancelada.',
  BILLING_CHARGE_CHANGED: 'A cobrança foi alterada por outra pessoa. Recarregue os dados.',
  BILLING_CHARGE_NOT_OPEN: 'A cobrança não está em aberto.',
  BILLING_PAYMENT_AMOUNT_INVALID: 'Informe o valor pago.',
  BILLING_PAYMENT_AMOUNT_MISMATCH: 'O valor pago precisa ser igual ao da cobrança. Pagamento parcial não é registrado.',
  BILLING_PAYMENT_DATE_INVALID: 'Informe uma data de pagamento até hoje.',
  BILLING_PAYMENT_METHOD_INVALID: 'Forma de pagamento inválida.',
  BILLING_PAYMENT_REFERENCE_REQUIRED: 'Informe a referência do comprovante (de 3 a 200 caracteres).',
  BILLING_PAYMENT_REFERENCE_USED: 'Este comprovante já quita outra cobrança. Confira a referência e a forma de pagamento.',
  BILLING_PAYMENT_NOT_FOUND: 'Pagamento não encontrado.',
  BILLING_PAYMENT_VOIDED: 'O pagamento já foi estornado.',
  BILLING_REFERENCE_NOT_FOUND: 'Condomínio, plano ou cobrança não encontrado.',
  BILLING_STATE_CONFLICT: 'Os dados financeiros mudaram ou não atendem às regras. Recarregue e confira.',
  BILLING_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação financeira. Atualize e confira antes de repetir.',
  FINANCE_PAYER_REQUIRED: 'Escolha o proprietário ou o responsável que paga.',
  FINANCE_PAYER_NAME_INVALID: 'Informe o nome do pagador com 3 a 120 caracteres.',
  FINANCE_DOCUMENT_INVALID: 'Informe um CPF ou CNPJ válido.',
  FINANCE_EMAIL_INVALID: 'Informe um e-mail válido do pagador.',
  FINANCE_REASON_REQUIRED: 'Informe o motivo, com 3 a 480 caracteres.',
  FINANCE_UNIT_REQUIRED: 'Escolha a unidade.',
  FINANCE_KIND_INVALID: 'Escolha o tipo de cobrança.',
  FINANCE_BILLING_INVALID: 'Escolha boleto ou PIX.',
  FINANCE_AMOUNT_INVALID: 'O valor vai de R$ 5,00 a R$ 1.000.000,00.',
  FINANCE_DESCRIPTION_INVALID: 'Descreva a cobrança com 3 a 200 caracteres.',
  FINANCE_DUE_DATE_INVALID: 'O vencimento vai de hoje a dois anos.',
  FINANCE_DUE_DAY_INVALID: 'O dia de vencimento vai de 1 a 28.',
  FINANCE_COMPETENCE_INVALID: 'A competência vai do mês atual a 12 meses à frente (AAAA-MM).',
  FINANCE_PERIOD_INVALID: 'O período do relatório tem de 1 a 24 meses.',
  FINANCE_STATE_INVALID: 'Situação de cobrança inválida.',
  FINANCE_VERSION_REQUIRED: 'Atualize a cobrança antes de continuar.',
  FINANCE_PAYER_MISSING: 'Cadastre o pagador da unidade antes de cobrar.',
  FINANCE_CHARGE_NOT_FOUND: 'Cobrança não encontrada.',
  FINANCE_FEE_NOT_FOUND: 'Taxa recorrente não encontrada.',
  FINANCE_CHANGED: 'Os dados foram alterados. Atualize antes de continuar.',
  FINANCE_CHARGE_CLOSED: 'Esta cobrança já foi paga, cancelada ou estornada.',
  FINANCE_CHARGE_ISSUING: 'A cobrança está sendo emitida. Atualize em instantes.',
  FINANCE_CHARGE_NOT_ISSUED: 'A cobrança ainda não foi emitida no Asaas.',
  FINANCE_CHARGE_NOT_PAYABLE: 'Esta cobrança não está em aberto.',
  FINANCE_ASAAS_REJECTED: 'O Asaas não permitiu a operação (por exemplo, cobrança já paga). Sincronize e confira.',
  FINANCE_ASAAS_UNAVAILABLE: 'O Asaas não respondeu. Atualize e confira antes de repetir.',
  FINANCE_RULE_VIOLATION: 'A operação não atende às regras do financeiro. Atualize e confira.',
  FINANCE_DEPENDENCY_UNAVAILABLE: 'Não foi possível concluir a operação financeira. Atualize e confira antes de repetir.',
  // Hierarquia de parceiro (V049, ADR-0011). Código conhecido evita que um 404 do parceiro pareça rota desligada.
  PARTNER_ACCESS_DENIED: 'Esta ação não está disponível para o seu papel no parceiro.',
  PARTNER_FORBIDDEN: 'Operação não permitida para este acesso.',
  PARTNER_NOT_FOUND: 'Parceiro não disponível para este acesso.',
  PARTNER_MEMBER_NOT_FOUND: 'Papel não encontrado neste parceiro. Atualize a lista.',
  PARTNER_SCHEDULE_NOT_FOUND: 'Escala não encontrada neste parceiro. Atualize a lista.',
  PARTNER_SELF_CHANGE_DENIED: 'O gestor não altera o próprio papel; peça à plataforma.',
  PARTNER_MEMBER_REVOKED: 'O papel já foi revogado.',
  PARTNER_SCHEDULE_CANCELLED: 'A escala já foi cancelada.',
  PARTNER_CONTRACT_ENDED: 'O contrato já foi encerrado.',
  PARTNER_CHANGED: 'O registro mudou desde a leitura. Atualize e tente de novo.',
  PARTNER_DUPLICATE: 'Já existe um registro igual ativo.',
  PARTNER_PERIOD_CONFLICT: 'O período conflita com outro registro ativo (escala sobreposta ou fora do contrato).',
  PARTNER_REFERENCE_INVALID: 'Conta, condomínio ou papel inexistente.',
  PARTNER_RULE_VIOLATED: 'A mudança não atende às regras do parceiro (parceiro ou condomínio inativo, escala fora do contrato ou do papel).',
  PARTNER_MEMBER_INVALID: 'Informe a conta (UUID), o papel e o nome de exibição com até 160 caracteres.',
  PARTNER_ROLE_INVALID: 'Papel de parceiro inválido.',
  PARTNER_SCHEDULE_INVALID: 'Informe o operador, o condomínio e o fim da escala, depois do início e em no máximo 31 dias.',
  PARTNER_UNAVAILABLE: 'Não foi possível concluir agora. Atualize e confira antes de repetir.',
  // Log de Segurança (V050, CPX-009/CPX-010).
  SECURITY_LOG_LIMIT_INVALID: 'Use um limite entre 1 e 200 eventos.',
  SECURITY_LOG_TYPE_INVALID: 'Tipo de evento inválido: use letras maiúsculas, números e _.',
  SECURITY_LOG_SEVERITY_INVALID: 'Use severidade informativa, de atenção ou crítica.',
  SECURITY_LOG_ACTOR_INVALID: 'Autor inválido.',
  SECURITY_LOG_PERIOD_INVALID: 'Período inválido: o início deve ser anterior ao fim, com no máximo 366 dias.',
  SECURITY_LOG_CURSOR_INVALID: 'Não foi possível carregar mais eventos. Refaça a busca.',
  SECURITY_LOG_EXPORT_REASON_REQUIRED: 'Informe o motivo da exportação (10 a 500 caracteres).',
  SECURITY_LOG_EXPORT_RANGE_INVALID: 'O intervalo pedido não existe no log deste condomínio.',
  SECURITY_LOG_EXPORT_TOO_LARGE: 'Exporte no máximo 5.000 eventos por vez.',
  SECURITY_LOG_CHAIN_BROKEN: 'A cadeia do log está quebrada neste intervalo. Verifique a integridade.',
  SECURITY_LOG_DEPENDENCY_UNAVAILABLE: 'Não foi possível consultar o log de segurança agora. Tente novamente.',
  // Troca de contexto (R4.4, V052).
  CONTEXT_SWITCH_INVALID: 'Não foi possível identificar o acesso escolhido. Volte para meus acessos e escolha de novo.',
  CONTEXT_SWITCH_UNAVAILABLE: 'Não foi possível registrar a entrada neste painel. Tente novamente.',
  // Comando de Segurança (CPX-012 a CPX-014, V054).
  SECURITY_COMMAND_UNAVAILABLE: 'Não foi possível concluir no Comando de Segurança agora. Nada foi gravado; tente novamente.',
  INCIDENT_NOT_FOUND: 'Incidente não encontrado.',
  INCIDENT_CHANGED: 'O incidente mudou desde a leitura. Atualize e tente de novo.',
  INCIDENT_CLOSED: 'O incidente já foi encerrado.',
  INCIDENT_ASSIGNEE_INVALID: 'O responsável precisa ser líder, supervisor ou operador ativo do parceiro.',
  INCIDENT_EVENT_NOT_FOUND: 'Evento do log não encontrado neste condomínio.',
  INCIDENT_LINKS_LIMIT: 'O incidente aceita no máximo 50 eventos anexados.',
  INCIDENT_DOSSIER_EMPTY: 'O incidente ainda não tem eventos no log.',
  INCIDENT_TIMELINE_TOO_LARGE: 'A linha do tempo passa de 500 eventos; consulte o log por período.',
  INCIDENT_DOSSIER_REASON_REQUIRED: 'Informe o motivo da exportação, com 10 a 500 caracteres.',
  REMOTE_OPEN_NOT_FOUND: 'Abertura remota não encontrada.',
  REMOTE_OPEN_ALREADY_REVIEWED: 'Esta abertura já foi revisada. Atualize a lista e corrija a revisão mais recente.',
  REMOTE_OPEN_SELF_REVIEW: 'Ninguém revisa a própria abertura remota.',
  REMOTE_OPEN_REVIEW_INVALID: 'Revisão inválida para esta abertura.',
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly uncertain = false,
    readonly retryAfterSeconds?: number,
    readonly retryUntil?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUUID = (value: unknown): value is string => typeof value === 'string' && uuidPattern.test(value);
export const isString = (value: unknown): value is string => typeof value === 'string';
export const isDate = (value: unknown): value is string => isString(value) && Number.isFinite(Date.parse(value));
export const isInteger = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
export const oneOf = <T extends string>(value: unknown, values: readonly T[]): value is T =>
  typeof value === 'string' && values.some((candidate) => candidate === value);

export function invalidResponse(uncertain = false): ApiError {
  return new ApiError(502, 'INVALID_API_RESPONSE',
    uncertain ? 'Não foi possível confirmar o resultado. Atualize os dados e confira o estado antes de tentar novamente.'
      : 'O sistema retornou uma resposta que não pôde ser validada. Tente novamente mais tarde.', uncertain);
}

function unknownCommand(): ApiError {
  return new ApiError(0, 'COMMAND_OUTCOME_UNKNOWN',
    'Não foi possível confirmar o resultado. Atualize os dados e confira o estado antes de tentar novamente.', true);
}

function session(value: unknown): Session {
  if (!record(value) || !isUUID(value.accountId) || !isDate(value.expiresAt)) throw invalidResponse(true);
  return { accountId: value.accountId, expiresAt: value.expiresAt };
}

function mfaStatus(value: unknown): MfaStatus {
  if (!record(value) || !oneOf(value.assuranceLevel, ['AAL1', 'AAL2']) || !Array.isArray(value.factors)
      || value.factors.length > 20) throw invalidResponse();
  return { assuranceLevel: value.assuranceLevel, factors: value.factors.map((item) => {
    if (!record(item) || !isUUID(item.factorId) || !oneOf(item.status, ['VERIFIED', 'UNVERIFIED'])
        || !(item.friendlyName === undefined || item.friendlyName === null || isString(item.friendlyName))) throw invalidResponse();
    return { factorId: item.factorId, status: item.status, friendlyName: isString(item.friendlyName) ? item.friendlyName : null };
  }) };
}

function totpEnrollment(value: unknown): TotpEnrollment {
  if (!record(value) || !isUUID(value.factorId) || !isString(value.otpauthUri) || !value.otpauthUri.startsWith('otpauth://totp/')
      || !isString(value.secret) || !/^[A-Z2-7]{16,128}=*$/.test(value.secret)
      || !(value.qrCodeDataUri === undefined || value.qrCodeDataUri === null
        || (isString(value.qrCodeDataUri) && value.qrCodeDataUri.startsWith('data:image/svg+xml')))) throw invalidResponse(true);
  return { factorId: value.factorId, otpauthUri: value.otpauthUri, secret: value.secret,
    qrCodeDataUri: isString(value.qrCodeDataUri) ? value.qrCodeDataUri : null };
}

function recoveryVerified(value: unknown): RecoveryVerified {
  if (!record(value) || typeof value.mfaRequired !== 'boolean' || !isDate(value.expiresAt)) throw invalidResponse();
  return { mfaRequired: value.mfaRequired, expiresAt: value.expiresAt };
}
function recoveryCompleted(value: unknown): RecoveryCompleted {
  if (!record(value) || value.status !== 'PASSWORD_CHANGED' || typeof value.otherSessionsEnded !== 'boolean') throw invalidResponse(true);
  return { status: 'PASSWORD_CHANGED', otherSessionsEnded: value.otherSessionsEnded };
}
/** Mesma política local do servidor: 12 a 72 caracteres, até 72 bytes e ao menos 4 caracteres diferentes. */
export function passwordProblem(password: string): string | null {
  const characters = Array.from(password);
  if (characters.length < 12 || characters.length > 72 || new TextEncoder().encode(password).length > 72
    || password.trim() === '' || new Set(characters).size < 4) return messages.PASSWORD_INVALID!;
  return null;
}
export const RECOVERY_LINK = /^[A-Za-z0-9_-]{16,512}$/;
export const PERMIT_LINK = /^[A-Za-z0-9_-]{43}$/;
/** O que quem abre o link de visita vê: sem unidade nem identificadores, só o primeiro nome. */
export interface PublicPermit {
  tenantName: string; visitorFirstName: string; purpose: string | null; validFrom: string; validUntil: string; timeZone: string;
  state: 'SCHEDULED' | 'ACTIVE';
}
export function publicPermit(value: unknown): PublicPermit {
  if (!record(value) || !isString(value.tenantName) || !value.tenantName || !isString(value.visitorFirstName) || !value.visitorFirstName
      || !(value.purpose === null || isString(value.purpose)) || !isDate(value.validFrom) || !isDate(value.validUntil)
      || !isString(value.timeZone) || !oneOf(value.state, ['SCHEDULED', 'ACTIVE']) || Date.parse(value.validUntil) <= Date.parse(value.validFrom)
      || Object.keys(value).some((key) => !['tenantName', 'visitorFirstName', 'purpose', 'validFrom', 'validUntil', 'timeZone', 'state'].includes(key)))
    throw invalidResponse();
  return { tenantName: value.tenantName, visitorFirstName: value.visitorFirstName, purpose: value.purpose as string | null,
    validFrom: value.validFrom, validUntil: value.validUntil, timeZone: value.timeZone, state: value.state };
}

/** Convite da gestão gera vínculo PENDING; convite de primeiro gestor (plataforma) gera vínculo ACTIVE. Outro estado é inválido. */
function invitationAccepted(value: unknown, expected: InvitationAccepted['status'] = 'PENDING'): InvitationAccepted {
  if (!record(value) || !isUUID(value.tenantId) || !isUUID(value.membershipId) || value.status !== expected) throw invalidResponse(true);
  return { tenantId: value.tenantId, membershipId: value.membershipId, status: expected };
}
function invitationFirstAccess(value: unknown, expected: InvitationAccepted['status'] = 'PENDING'): InvitationFirstAccess {
  const accepted = invitationAccepted(value, expected);
  if (!record(value) || typeof value.sessionStarted !== 'boolean'
      || (value.sessionStarted ? !isUUID(value.accountId) || !isDate(value.expiresAt) : value.accountId !== null || value.expiresAt !== null))
    throw invalidResponse(true);
  return { ...accepted, sessionStarted: value.sessionStarted, accountId: value.accountId as string | null,
    expiresAt: value.expiresAt as string | null };
}

const MEMBERSHIP_ROLES = ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER', 'CONCIERGE', 'MAINTENANCE', 'MANAGER', 'SUPPORT'] as const;

/** Vínculo válido; null quando o papel é novo e esta versão da web ainda não o conhece (fica de fora, sem derrubar os demais). */
function membership(value: unknown): Membership | null {
  if (!record(value) || !isUUID(value.membershipId) || !isUUID(value.tenantId)
      || !isString(value.tenantName) || !isString(value.timeZone)
      || !(value.unitId === null || isUUID(value.unitId))
      || typeof value.role !== 'string' || !/^[A-Z][A-Z_]{1,39}$/.test(value.role))
    throw invalidResponse();
  if (!oneOf(value.role, MEMBERSHIP_ROLES)) return null;
  return { membershipId: value.membershipId, tenantId: value.tenantId, tenantName: value.tenantName,
    timeZone: value.timeZone, unitId: value.unitId, role: value.role };
}

/**
 * Módulos liberados por condomínio (V045). Ausente (servidor anterior): null, sem esconder nada. Módulo novo que esta
 * versão da web não conhece fica de fora, como o papel novo; condomínio repetido ou valor malformado derruba a resposta.
 */
function tenantModuleList(value: unknown): TenantModules[] | null {
  if (value === undefined) return null;
  if (!Array.isArray(value)) throw invalidResponse();
  const seen = new Set<string>();
  return value.map((item) => {
    if (!record(item) || !isUUID(item.tenantId) || !Array.isArray(item.enabled)
        || !item.enabled.every((module) => typeof module === 'string' && /^[a-z][a-z_]{1,39}$/.test(module))
        || seen.has(item.tenantId.toLowerCase())) throw invalidResponse();
    seen.add(item.tenantId.toLowerCase());
    return { tenantId: item.tenantId, enabled: tenantModuleIds.filter((module) => (item.enabled as unknown[]).includes(module)) };
  });
}

/** Painel de destino de cada papel de parceiro (PartnerCapability.panel no servidor). */
export const partnerRolePanels: Readonly<Record<PartnerRole, PartnerPanel>> = { PARTNER_ADMIN: 'gestao', SECURITY_LEAD: 'comando',
  SECURITY_SUPERVISOR: 'turno', REMOTE_OPERATOR: 'console', TECHNICIAN: 'tecnico' };

/**
 * Contextos de parceiro (V049). Ausente (servidor anterior ou flag desligada): lista vazia. Papel novo que esta versão
 * da web não conhece fica de fora, como o vínculo; capacidade nova é ignorada (só esconderia menu, a API confere).
 * Painel diferente do papel, papel repetido ou valor malformado derrubam a resposta.
 */
function partnerContextList(value: unknown): PartnerContext[] {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > 100) throw invalidResponse();
  const seen = new Set<string>();
  return value.flatMap((item): PartnerContext[] => {
    if (!record(item) || !isUUID(item.memberId) || !isUUID(item.partnerId) || !isString(item.partnerName) || !item.partnerName.trim()
        || typeof item.role !== 'string' || !/^[A-Z][A-Z_]{1,39}$/.test(item.role) || typeof item.panel !== 'string'
        || !Array.isArray(item.capabilities) || !item.capabilities.every((capability) => typeof capability === 'string')
        || !Array.isArray(item.tenants) || item.tenants.length > 1000 || seen.has(item.memberId.toLowerCase())) throw invalidResponse();
    seen.add(item.memberId.toLowerCase());
    const tenantIds = new Set<string>();
    const tenants = item.tenants.map((tenant) => {
      if (!record(tenant) || !isUUID(tenant.tenantId) || !isString(tenant.tenantName) || !isString(tenant.timeZone)
          || tenantIds.has(tenant.tenantId.toLowerCase())) throw invalidResponse();
      tenantIds.add(tenant.tenantId.toLowerCase());
      return { tenantId: tenant.tenantId, tenantName: tenant.tenantName, timeZone: tenant.timeZone };
    });
    if (!oneOf(item.role, partnerRoles)) return [];
    if (item.panel !== partnerRolePanels[item.role]) throw invalidResponse();
    return [{ memberId: item.memberId, partnerId: item.partnerId, partnerName: item.partnerName, role: item.role, panel: partnerRolePanels[item.role],
      capabilities: partnerCapabilities.filter((capability) => (item.capabilities as unknown[]).includes(capability)), tenants }];
  });
}

function identity(value: unknown): CurrentIdentity {
  if (!record(value) || !isUUID(value.accountId) || !Array.isArray(value.memberships)) throw invalidResponse();
  return { accountId: value.accountId,
    memberships: value.memberships.map(membership).filter((item): item is Membership => item !== null),
    tenantModules: tenantModuleList(value.tenantModules), partnerContexts: partnerContextList(value.partnerContexts),
    demoTenantIds: demoTenantList(value.demoTenantIds) };
}

/** Condomínios DEMO (V053): ausente (servidor anterior) vira lista vazia; formato inválido recusa a resposta. */
function demoTenantList(value: unknown): string[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || !value.every(isUUID)) throw invalidResponse();
  return [...new Set(value.map((id) => id.toLowerCase()))];
}

/** Módulo desligado pela central para o condomínio (403 MODULE_DISABLED): não é perda de vínculo nem de sessão. */
export function isModuleDisabled(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'MODULE_DISABLED';
}

function unit(value: unknown): Unit {
  if (!record(value) || !isUUID(value.id) || !isString(value.label)
      || !(value.blockId === null || isUUID(value.blockId))
      || !(value.blockName === null || isString(value.blockName))) throw invalidResponse();
  return { id: value.id, label: value.label, blockId: value.blockId, blockName: value.blockName };
}

function policy(value: unknown): ReservationPolicy {
  if (!record(value) || value.schemaVersion !== 1 || !Array.isArray(value.weekDays)
      || value.weekDays.length === 0 || !value.weekDays.every((day) => isInteger(day) && day >= 1 && day <= 7)
      || !isString(value.opensAt) || !isString(value.closesAt)
      || !isInteger(value.minimumLeadMinutes) || !isInteger(value.maximumAdvanceDays)
      || !isInteger(value.maximumDurationMinutes) || !isInteger(value.maximumActivePerUnit)
      || !isInteger(value.cancellationLeadMinutes)) throw invalidResponse();
  return { schemaVersion: 1, weekDays: [...value.weekDays] as number[], opensAt: value.opensAt, closesAt: value.closesAt,
    minimumLeadMinutes: value.minimumLeadMinutes, maximumAdvanceDays: value.maximumAdvanceDays,
    maximumDurationMinutes: value.maximumDurationMinutes, maximumActivePerUnit: value.maximumActivePerUnit,
    cancellationLeadMinutes: value.cancellationLeadMinutes };
}

function amenity(value: unknown): Amenity {
  if (!record(value) || !isUUID(value.amenityId) || !isString(value.name) || !isInteger(value.capacity)
      || !isInteger(value.policyVersion) || !isInteger(value.feeCents) || !isInteger(value.bufferMinutes)
      || typeof value.approvalRequired !== 'boolean'
      || !oneOf(value.bookingState, ['POLICY_NOT_CONFIGURED', 'AVAILABLE_FOR_APPROVAL_REQUEST', 'AVAILABLE_FOR_REQUEST']))
    throw invalidResponse();
  return { amenityId: value.amenityId, name: value.name, capacity: value.capacity, policyVersion: value.policyVersion,
    feeCents: value.feeCents, bufferMinutes: value.bufferMinutes, approvalRequired: value.approvalRequired,
    bookingState: value.bookingState, policy: value.policy === null ? null : policy(value.policy) };
}

export function reservation(value: unknown, uncertain = false): Reservation {
  if (!record(value) || !isUUID(value.reservationId) || !isUUID(value.unitId) || !isUUID(value.amenityId)
      || !oneOf(value.status, ['HELD', 'PENDING_APPROVAL', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'EXPIRED'])
      || !isDate(value.startsAt) || !isDate(value.endsAt) || !isDate(value.occupiedFrom) || !isDate(value.occupiedUntil)
      || !isInteger(value.acceptedPolicyVersion) || !isInteger(value.feeCents) || !isInteger(value.version)
      || !(value.decisionReason === undefined || value.decisionReason === null || isString(value.decisionReason)))
    throw invalidResponse(uncertain);
  return { reservationId: value.reservationId, unitId: value.unitId, amenityId: value.amenityId, status: value.status,
    startsAt: value.startsAt, endsAt: value.endsAt, occupiedFrom: value.occupiedFrom, occupiedUntil: value.occupiedUntil,
    acceptedPolicyVersion: value.acceptedPolicyVersion, feeCents: value.feeCents, version: value.version,
    decisionReason: typeof value.decisionReason === 'string' ? value.decisionReason : null };
}

function availability(value: unknown, expected: {
  tenantId: string; unitId: string; amenityId: string; timeZone: string; from: string; until: string;
}): AmenityAvailability {
  const fields = ['tenantId', 'unitId', 'amenityId', 'timeZone', 'from', 'until', 'observedAt', 'blockedIntervals'];
  if (!record(value) || Object.keys(value).some(field => !fields.includes(field))
      || !isUUID(value.tenantId) || value.tenantId.toLowerCase() !== expected.tenantId.toLowerCase()
      || !isUUID(value.unitId) || value.unitId.toLowerCase() !== expected.unitId.toLowerCase()
      || !isUUID(value.amenityId) || value.amenityId.toLowerCase() !== expected.amenityId.toLowerCase()
      || value.timeZone !== expected.timeZone || !isDate(value.from) || !isDate(value.until)
      || instantMicros(value.from) !== instantMicros(expected.from) || instantMicros(value.until) !== instantMicros(expected.until)
      || !isDate(value.observedAt) || !Array.isArray(value.blockedIntervals) || value.blockedIntervals.length > 1000)
    throw invalidResponse();
  const from = instantMicros(value.from); const until = instantMicros(value.until);
  if (from === null || until === null || !(from < until) || until - from > 31n * 86_400_000_000n
      || instantMicros(value.observedAt) === null) throw invalidResponse();
  let previousUntil: bigint | null = null;
  const blockedIntervals = value.blockedIntervals.map(item => {
    if (!record(item) || Object.keys(item).some(field => !['occupiedFrom', 'occupiedUntil'].includes(field))
        || !isDate(item.occupiedFrom) || !isDate(item.occupiedUntil)) throw invalidResponse();
    const start = instantMicros(item.occupiedFrom); const end = instantMicros(item.occupiedUntil);
    if (start === null || end === null || !(start < end) || start < from || end > until
        || (previousUntil !== null && start <= previousUntil)) throw invalidResponse();
    previousUntil = end;
    return { occupiedFrom: item.occupiedFrom, occupiedUntil: item.occupiedUntil };
  });
  return { tenantId: value.tenantId, unitId: value.unitId, amenityId: value.amenityId,
    timeZone: expected.timeZone, from: value.from, until: value.until, observedAt: value.observedAt, blockedIntervals };
}

export function list<T>(value: unknown, decode: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) throw invalidResponse();
  return value.map(decode);
}

export function identifier(value: string): string {
  if (!isUUID(value)) throw new ApiError(400, 'INVALID_RESOURCE_ID', 'O contexto selecionado não é válido. Selecione novamente.');
  return value;
}

export function operationKey(value: string): string {
  if (typeof value !== 'string' || !/^[\x21-\x7e]{16,200}$/.test(value))
    throw new ApiError(400, 'INVALID_IDEMPOTENCY_KEY', 'Não foi possível preparar esta solicitação. Reabra o formulário.');
  return value;
}

function abortError(): DOMException { return new DOMException('Consulta cancelada.', 'AbortError'); }
function checkSignal(signal?: AbortSignal): void { if (signal?.aborted) throw abortError(); }

function abortable<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(abortError());
  return new Promise<T>((resolve, reject) => {
    const abort = () => { signal.removeEventListener('abort', abort); reject(abortError()); };
    signal.addEventListener('abort', abort, { once: true });
    promise.then((value) => { signal.removeEventListener('abort', abort); resolve(value); },
      (error: unknown) => { signal.removeEventListener('abort', abort); reject(error); });
  });
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT';
  body?: string;
  key?: string;
  signal?: AbortSignal;
}
interface Reply { response: Response; value: unknown; }
interface Csrf { headerName: 'X-CSRF-Token'; token: string; }

function csrfResponse(value: unknown): Csrf {
  if (!record(value) || value.headerName !== 'X-CSRF-Token' || !isString(value.token)
      || !/^[\x21-\x7e]{1,512}$/.test(value.token)) throw invalidResponse();
  return { headerName: 'X-CSRF-Token', token: value.token };
}

/** Cliente BFF da própria origem. Nenhuma credencial do provedor fica no navegador. */
export class CondPulseApi {
  private readonly fetcher: typeof globalThis.fetch;
  private readonly now: () => number;
  private readonly authCooldowns = new Map<string, number>();
  private generation = 0;
  private authTail: Promise<void> = Promise.resolve();
  private refreshFlight: { generation: number; promise: Promise<Session> } | undefined;
  private csrfFlight: { generation: number; promise: Promise<Csrf> } | undefined;
  private readonly expiredListeners = new Set<(failure: unknown) => void>();
  private readonly moduleListeners = new Set<(failure: ApiError) => void>();

  constructor(options: { fetch?: typeof globalThis.fetch; now?: () => number } = {}) {
    this.fetcher = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.now = options.now ?? (() => globalThis.performance.now());
  }

  /** Aviso de 403 MODULE_DISABLED em qualquer chamada: a casca relê /v1/me para atualizar o menu. */
  onModuleDisabled(listener: (failure: ApiError) => void): () => void {
    this.moduleListeners.add(listener);
    return () => { this.moduleListeners.delete(listener); };
  }

  onSessionExpired(listener: (failure: unknown) => void): () => void {
    this.expiredListeners.add(listener);
    return () => { this.expiredListeners.delete(listener); };
  }

  /** Limpa memória e impede respostas de outra sessão; não afirma revogação no servidor. */
  clearSession(): void {
    this.generation += 1; this.csrfFlight = undefined;
    this.authCooldowns.delete('/auth/refresh');
    // A espera de login não guarda identidade e continua válida ao limpar a sessão.
  }

  private expire(generation: number, failure: unknown): void {
    if (generation !== this.generation) return;
    this.clearSession();
    for (const listener of this.expiredListeners) {
      try { listener(failure); } catch { /* Um consumidor não pode impedir a limpeza dos demais. */ }
    }
  }

  private ensureCurrent(generation: number, signal?: AbortSignal): void {
    checkSignal(signal);
    if (generation !== this.generation) throw abortError();
  }

  private auth<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.authTail.then(operation);
    this.authTail = next.then(() => undefined, () => undefined);
    return next;
  }

  private async csrf(generation: number, signal?: AbortSignal): Promise<Csrf> {
    if (!this.csrfFlight || this.csrfFlight.generation !== generation) {
      const promise = this.send('/auth/csrf', {}, generation, false).then(({ value }) => csrfResponse(value));
      this.csrfFlight = { generation, promise };
      void promise.then(() => { if (this.csrfFlight?.promise === promise) this.csrfFlight = undefined; },
        () => { if (this.csrfFlight?.promise === promise) this.csrfFlight = undefined; });
    }
    const result = await abortable(this.csrfFlight.promise, signal);
    this.ensureCurrent(generation, signal);
    return result;
  }

  private async send(path: string, options: RequestOptions, generation: number, useCsrf = true): Promise<Reply> {
    this.ensureCurrent(generation, options.signal);
    const cooldownKey = COOLDOWN_KEYS[path] ?? path;
    const deadline = this.authCooldowns.get(cooldownKey);
    const remaining = remainingCooldown(deadline, this.now());
    if (remaining > 0) throw new ApiError(429, 'AUTH_RATE_LIMITED', messages.AUTH_RATE_LIMITED!, false, remaining, deadline);
    const mutation = options.method === 'POST' || options.method === 'PUT';
    const headers = new Headers({ Accept: 'application/json' });
    if (mutation && useCsrf) {
      const token = await this.csrf(generation, options.signal);
      headers.set(token.headerName, token.token);
    }
    if (options.body !== undefined) headers.set('Content-Type', 'application/json');
    if (options.key !== undefined) headers.set('Idempotency-Key', options.key);
    this.ensureCurrent(generation, options.signal);
    let response: Response;
    try {
      response = await this.fetcher(path, { method: options.method ?? 'GET', headers,
        ...(options.body !== undefined ? { body: options.body } : {}),
        ...(options.signal ? { signal: options.signal } : {}), credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
    } catch (error: unknown) {
      if (!mutation && (options.signal?.aborted || (error instanceof Error && error.name === 'AbortError'))) throw abortError();
      if (mutation) throw unknownCommand();
      throw new ApiError(0, 'NETWORK_UNAVAILABLE', 'Não foi possível conectar ao sistema. Confira sua conexão e tente novamente.');
    }
    const ensureResponseCurrent = () => {
      try { this.ensureCurrent(generation, options.signal); }
      catch { if (mutation) throw unknownCommand(); throw abortError(); }
    };
    ensureResponseCurrent();
    let value: unknown;
    if (response.status !== 204) {
      const contentType = response.headers.get('Content-Type') ?? '';
      if (contentType.includes('application/json') || contentType.includes('application/problem+json')) {
        try { value = await response.json() as unknown; }
        catch {
          if (!mutation && options.signal?.aborted) throw abortError();
          if (response.ok || response.status >= 500) throw invalidResponse(mutation);
          // Um401 conhecido continua exigindo novo acesso mesmo se o corpo vier inválido.
          value = undefined;
        }
      } else if (response.ok) throw invalidResponse(mutation);
    }
    ensureResponseCurrent();
    if (!response.ok) {
      if (response.status === 429) {
        const seconds = parseRetryAfterSeconds(response.headers.get('Retry-After'));
        const until = Math.max(this.authCooldowns.get(cooldownKey) ?? 0, this.now() + seconds * 1_000);
        if (COOLDOWN_KEYS[path]) this.authCooldowns.set(cooldownKey, until);
        throw new ApiError(429, 'AUTH_RATE_LIMITED', messages.AUTH_RATE_LIMITED!, false, seconds, until);
      }
      const candidate = record(value) && isString(value.code) ? value.code : '';
      const code = Object.hasOwn(messages, candidate) ? candidate
        : response.status === 401 ? 'AUTHENTICATION_REQUIRED' : response.status === 404 ? 'API_NOT_AVAILABLE' : 'HTTP_ERROR';
      const message = messages[code] ?? (response.status === 404 ? 'Esta função ainda não está disponível neste ambiente.'
        : response.status === 429 ? 'Aguarde antes de tentar novamente.'
          : response.status === 403 ? 'Não foi possível validar esta operação. Atualize a página e tente novamente.'
            : 'Não foi possível concluir a operação. Tente novamente mais tarde.');
      throw new ApiError(response.status, code, message, mutation && response.status >= 500);
    }
    return { response, value };
  }

  private async request(path: string, options: RequestOptions = {}): Promise<Reply> {
    const generation = this.generation;
    await abortable(this.authTail, options.signal);
    this.ensureCurrent(generation, options.signal);
    try {
      try { return await this.send(path, options, generation); }
      catch (error: unknown) {
        if (!(error instanceof ApiError) || error.status !== 401 || error.code !== 'SESSION_REFRESH_REQUIRED') throw error;
        // O filtro negou antes do controller: pode reenviar UMA vez, com o mesmo corpo/chave.
        await abortable(this.refresh(), options.signal);
        this.ensureCurrent(generation, options.signal);
        return await this.send(path, options, generation);
      }
    } catch (error: unknown) {
      if (error instanceof ApiError && (error.status === 401 || error.code === 'ACCOUNT_INACTIVE')) this.expire(generation, error);
      if (error instanceof ApiError && error.code === 'MODULE_DISABLED' && generation === this.generation) {
        for (const listener of this.moduleListeners) {
          try { listener(error); } catch { /* Um consumidor não pode impedir os demais. */ }
        }
      }
      throw error;
    }
  }

  async login(credentials: { email: string; password: string }, signal?: AbortSignal): Promise<Session> {
    const body = JSON.stringify({ email: credentials.email, password: credentials.password });
    return this.auth(async () => {
      const reply = await this.send('/auth/login', { method: 'POST', body, ...(signal ? { signal } : {}) }, this.generation);
      const result = session(reply.value);
      this.clearSession();
      return result;
    });
  }

  async refresh(signal?: AbortSignal): Promise<Session> {
    checkSignal(signal);
    const generation = this.generation;
    if (!this.refreshFlight || this.refreshFlight.generation !== generation) {
      const promise = this.auth(async () => {
        try {
          const reply = await this.send('/auth/refresh', { method: 'POST' }, generation);
          return session(reply.value);
        } catch (error: unknown) {
          const preserved = error instanceof ApiError && (
            error.status === 409 && error.code === 'SESSION_REFRESH_IN_PROGRESS'
            || error.status === 429
            || error.status === 503 && error.code === 'AUTH_RATE_LIMIT_UNAVAILABLE');
          if (!preserved)
            this.expire(generation, error);
          throw error;
        }
      });
      this.refreshFlight = { generation, promise };
      void promise.then(() => { if (this.refreshFlight?.promise === promise) this.refreshFlight = undefined; },
        () => { if (this.refreshFlight?.promise === promise) this.refreshFlight = undefined; });
    }
    return abortable(this.refreshFlight.promise, signal);
  }

  async logout(signal?: AbortSignal): Promise<void> {
    return this.auth(async () => {
      try { await this.send('/auth/logout', { method: 'POST', ...(signal ? { signal } : {}) }, this.generation); }
      finally { this.clearSession(); }
    });
  }

  async currentIdentity(signal?: AbortSignal): Promise<CurrentIdentity> {
    return identity((await this.request('/v1/me', signal ? { signal } : {})).value);
  }

  /** Confere o contrato do BFF sem guardar nem compartilhar o token com mutações. */
  async verifyLoginAvailable(signal?: AbortSignal): Promise<void> {
    const generation = this.generation;
    await abortable(this.authTail, signal);
    this.ensureCurrent(generation, signal);
    const { value } = await this.send('/auth/csrf', signal ? { signal } : {}, generation, false);
    this.ensureCurrent(generation, signal);
    csrfResponse(value);
  }
  async units(tenantId: string, signal?: AbortSignal): Promise<Unit[]> {
    return list((await this.request(`/v1/tenants/${identifier(tenantId)}/units`, signal ? { signal } : {})).value, unit);
  }
  async amenities(tenantId: string, signal?: AbortSignal): Promise<Amenity[]> {
    return list((await this.request(`/v1/tenants/${identifier(tenantId)}/amenities`, signal ? { signal } : {})).value, amenity);
  }
  async amenityAvailability(tenantId: string, unitId: string, amenityId: string,
    range: { from: string; until: string }, timeZone: string, signal?: AbortSignal): Promise<AmenityAvailability> {
    const query = new URLSearchParams({ unitId: identifier(unitId), from: range.from, until: range.until });
    const { value } = await this.request(`/v1/tenants/${identifier(tenantId)}/amenities/${identifier(amenityId)}/availability?${query}`,
      signal ? { signal } : {});
    return availability(value, { tenantId, unitId, amenityId, timeZone, ...range });
  }
  async reservations(tenantId: string, unitId: string, range: { from: string; until: string }, signal?: AbortSignal): Promise<ReservationPage> {
    const query = new URLSearchParams({ unitId: identifier(unitId), from: range.from, until: range.until });
    const { value } = await this.request(`/v1/tenants/${identifier(tenantId)}/reservations?${query}`, signal ? { signal } : {});
    if (!record(value) || typeof value.truncated !== 'boolean') throw invalidResponse();
    return { reservations: list(value.reservations, (item) => reservation(item)), truncated: value.truncated };
  }
  async createReservation(tenantId: string, body: CreateReservation, key: string, signal?: AbortSignal): Promise<MutationResult<Reservation>> {
    operationKey(key);
    const reply = await this.request(`/v1/tenants/${identifier(tenantId)}/reservations`,
      { method: 'POST', body: JSON.stringify(body), key, ...(signal ? { signal } : {}) });
    return { data: reservation(reply.value, true), replayed: reply.response.headers.get('Idempotency-Replayed') === 'true' };
  }
  async cancelReservation(tenantId: string, reservationId: string, body: CancelReservation, key: string, signal?: AbortSignal): Promise<MutationResult<Reservation>> {
    operationKey(key);
    const reply = await this.request(`/v1/tenants/${identifier(tenantId)}/reservations/${identifier(reservationId)}/cancel`,
      { method: 'POST', body: JSON.stringify(body), key, ...(signal ? { signal } : {}) });
    return { data: reservation(reply.value, true), replayed: reply.response.headers.get('Idempotency-Replayed') === 'true' };
  }
  /**
   * Transporte JSON para módulos de domínio (gestão). Mantém sessão, CSRF, renovação única e expiração do cliente;
   * toda resposta passa por um parser estrito antes de chegar à interface.
   */
  async call<T>(path: string, parse: (value: unknown, response: Response) => T,
    options: { method?: 'GET' | 'POST' | 'PUT'; body?: unknown; key?: string; signal?: AbortSignal } = {}): Promise<T> {
    if (options.key !== undefined) operationKey(options.key);
    const reply = await this.request(path, { ...(options.method ? { method: options.method } : {}),
      ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
      ...(options.key !== undefined ? { key: options.key } : {}), ...(options.signal ? { signal: options.signal } : {}) });
    return parse(reply.value, reply.response);
  }
  async mfaStatus(signal?: AbortSignal): Promise<MfaStatus> {
    return mfaStatus((await this.request('/auth/mfa', signal ? { signal } : {})).value);
  }
  /** O segredo e o QR aparecem uma única vez; quem chama não deve guardá-los em cache. */
  async enrollTotp(signal?: AbortSignal): Promise<TotpEnrollment> {
    return totpEnrollment((await this.request('/auth/mfa/enroll', { method: 'POST', ...(signal ? { signal } : {}) })).value);
  }
  /**
   * Sucesso troca a sessão por outra AAL2 da mesma conta: o CSRF anterior deixa de valer. Consultas em andamento
   * pertencem à mesma conta e não são descartadas (descartá-las derrubaria a tela de acesso sem motivo).
   */
  async verifyTotp(factorId: string, code: string, signal?: AbortSignal): Promise<Session> {
    identifier(factorId);
    if (!/^[0-9]{6}$/.test(code)) throw new ApiError(400, 'MFA_CODE_FORMAT', 'Digite os 6 números exibidos no aplicativo autenticador.');
    const reply = await this.request('/auth/mfa/verify', { method: 'POST', body: JSON.stringify({ factorId, code }), ...(signal ? { signal } : {}) });
    const result = session(reply.value);
    this.csrfFlight = undefined;
    return result;
  }
  /**
   * Recuperação de acesso: chamadas anônimas com CSRF, sem renovação de sessão nem expiração do contexto
   * autenticado. O pedido é neutro; a conclusão encerra sessão e CSRF no servidor.
   */
  async requestRecovery(email: string, signal?: AbortSignal): Promise<void> {
    const reply = await this.send('/auth/recovery', { method: 'POST', body: JSON.stringify({ email }), ...(signal ? { signal } : {}) },
      this.generation);
    if (reply.response.status !== 202 || !record(reply.value) || reply.value.status !== 'RECOVERY_REQUESTED') throw invalidResponse(true);
  }
  async verifyRecovery(tokenHash: string, signal?: AbortSignal): Promise<RecoveryVerified> {
    if (!RECOVERY_LINK.test(tokenHash)) throw new ApiError(400, 'RECOVERY_LINK_INVALID', messages.RECOVERY_LINK_INVALID!);
    // O link é de uso único no provedor: resposta perdida ou inválida encerra a tentativa nesta tela.
    try {
      const reply = await this.send('/auth/recovery/verify', { method: 'POST', body: JSON.stringify({ tokenHash }),
        ...(signal ? { signal } : {}) }, this.generation);
      return recoveryVerified(reply.value);
    } catch (error) {
      if (error instanceof ApiError && (error.uncertain || error.code === 'INVALID_API_RESPONSE'))
        throw new ApiError(error.status, 'RECOVERY_LINK_UNCERTAIN', messages.RECOVERY_LINK_UNCERTAIN!, true);
      throw error;
    }
  }
  async completeRecovery(password: string, code: string | null, signal?: AbortSignal): Promise<RecoveryCompleted> {
    const problem = passwordProblem(password);
    if (problem) throw new ApiError(400, 'PASSWORD_INVALID', problem);
    if (code !== null && !/^[0-9]{6}$/.test(code)) throw new ApiError(400, 'MFA_CODE_FORMAT', 'Digite os 6 números exibidos no aplicativo autenticador.');
    // Sem confirmação explícita a troca pode ou não ter ocorrido: orientar a entrar com a nova senha, sem repetir.
    let result: RecoveryCompleted;
    try {
      const reply = await this.send('/auth/recovery/complete', { method: 'POST', body: JSON.stringify({ password, code }),
        ...(signal ? { signal } : {}) }, this.generation);
      result = recoveryCompleted(reply.value);
    } catch (error) {
      if (error instanceof ApiError && (error.uncertain || error.code === 'INVALID_API_RESPONSE'))
        throw new ApiError(error.status, 'RECOVERY_OUTCOME_UNKNOWN', messages.RECOVERY_OUTCOME_UNKNOWN!, true);
      throw error;
    }
    this.clearSession();
    return result;
  }
  /**
   * Link de convite de visita (CP-037), sem conta: o token vai no corpo com CSRF, nunca na URL do servidor. Resposta
   * neutra para link desconhecido, revogado ou vencido.
   */
  async viewPermitLink(token: string, signal?: AbortSignal): Promise<PublicPermit> {
    if (!PERMIT_LINK.test(token)) throw new ApiError(404, 'PERMIT_LINK_UNAVAILABLE', messages.PERMIT_LINK_UNAVAILABLE!);
    const reply = await this.send('/auth/permit-links/view', { method: 'POST', body: JSON.stringify({ token }), ...(signal ? { signal } : {}) },
      this.generation);
    return publicPermit(reply.value);
  }
  /**
   * Primeiro acesso por convite: entra com a senha do provedor e aceita o convite na mesma chamada, para quem ainda
   * não tem conta local. Com sessão iniciada o CSRF anterior deixa de valer. Resultado incerto não é repetido.
   */
  async acceptInvitationWithLogin(input: { email: string; password: string; token: string; kind?: InvitationKind }, signal?: AbortSignal): Promise<InvitationFirstAccess> {
    if (!/^[A-Za-z0-9_-]{43}$/.test(input.token)) throw new ApiError(404, 'INVITATION_NOT_AVAILABLE', messages.INVITATION_NOT_AVAILABLE!);
    const manager = input.kind === 'manager';
    const body = JSON.stringify({ email: input.email, password: input.password, token: input.token });
    return this.auth(async () => {
      try {
        const reply = await this.send(manager ? '/auth/manager-invitation/accept' : '/auth/invitation/accept',
          { method: 'POST', body, ...(signal ? { signal } : {}) }, this.generation);
        const result = invitationFirstAccess(reply.value, manager ? 'ACTIVE' : 'PENDING');
        this.clearSession();
        return result;
      } catch (error) {
        if (error instanceof ApiError && (error.uncertain || error.code === 'INVALID_API_RESPONSE'))
          throw new ApiError(error.status, 'INVITATION_FIRST_ACCESS_UNKNOWN', messages.INVITATION_FIRST_ACCESS_UNKNOWN!, true);
        throw error;
      }
    });
  }
  async acceptInvitation(token: string, signal?: AbortSignal, kind: InvitationKind = 'membership'): Promise<InvitationAccepted> {
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new ApiError(400, 'INVITATION_NOT_AVAILABLE', messages.INVITATION_NOT_AVAILABLE!);
    const manager = kind === 'manager';
    const reply = await this.request(manager ? '/v1/manager-invitations/accept' : '/v1/invitations/accept',
      { method: 'POST', body: JSON.stringify({ token }), ...(signal ? { signal } : {}) });
    return invitationAccepted(reply.value, manager ? 'ACTIVE' : 'PENDING');
  }
}

/** Convite da gestão (vínculo pendente) ou convite de primeiro gestor emitido pela plataforma (vínculo ativo). */
export type InvitationKind = 'membership' | 'manager';

/** Uma chave por intenção. Quem oferece retry deve conservar esta chave e os mesmos dados. */
export function newOperationKey(): string { return `web:${globalThis.crypto.randomUUID()}`; }
export const api = new CondPulseApi();
