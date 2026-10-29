import type {
  Amenity, AmenityAvailability, CancelReservation, CreateReservation, CurrentIdentity, InvitationAccepted, InvitationFirstAccess, Membership,
  MfaStatus, MutationResult, RecoveryCompleted, RecoveryVerified, Reservation, ReservationPage, ReservationPolicy, Session,
  TotpEnrollment, Unit,
} from './types';
import { parseRetryAfterSeconds, remainingCooldown } from './cooldown';
import { instantMicros } from './instant';

/**
 * Rotas que guardam a espera do 429. O primeiro acesso por convite consome o mesmo balde de tentativas do login no
 * servidor, então as duas rotas compartilham a espera.
 */
const COOLDOWN_KEYS: Readonly<Record<string, string>> = {
  '/auth/login': '/auth/login', '/auth/invitation/accept': '/auth/login', '/auth/refresh': '/auth/refresh',
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
  TENANT_CHANGED: 'O condomínio foi alterado por outra pessoa. Recarregue os dados antes de salvar.',
  TENANT_NOT_FOUND: 'Condomínio não encontrado.',
  TENANT_NAME_INVALID: 'Informe o nome do condomínio com até 160 caracteres.',
  TENANT_STATE_INVALID: 'Informe se o condomínio fica ativo ou suspenso.',
  TENANT_VERSION_REQUIRED: 'Recarregue os dados do condomínio antes de salvar.',
  AUDIT_REASON_REQUIRED: 'Informe o motivo da alteração, com até 500 caracteres.',
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

function invitationAccepted(value: unknown): InvitationAccepted {
  if (!record(value) || !isUUID(value.tenantId) || !isUUID(value.membershipId) || value.status !== 'PENDING') throw invalidResponse(true);
  return { tenantId: value.tenantId, membershipId: value.membershipId, status: 'PENDING' };
}
function invitationFirstAccess(value: unknown): InvitationFirstAccess {
  const accepted = invitationAccepted(value);
  if (!record(value) || typeof value.sessionStarted !== 'boolean'
      || (value.sessionStarted ? !isUUID(value.accountId) || !isDate(value.expiresAt) : value.accountId !== null || value.expiresAt !== null))
    throw invalidResponse(true);
  return { ...accepted, sessionStarted: value.sessionStarted, accountId: value.accountId as string | null,
    expiresAt: value.expiresAt as string | null };
}

function membership(value: unknown): Membership {
  if (!record(value) || !isUUID(value.membershipId) || !isUUID(value.tenantId)
      || !isString(value.tenantName) || !isString(value.timeZone)
      || !(value.unitId === null || isUUID(value.unitId))
      || !oneOf(value.role, ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER', 'CONCIERGE', 'MANAGER', 'SUPPORT']))
    throw invalidResponse();
  return { membershipId: value.membershipId, tenantId: value.tenantId, tenantName: value.tenantName,
    timeZone: value.timeZone, unitId: value.unitId, role: value.role };
}

function identity(value: unknown): CurrentIdentity {
  if (!record(value) || !isUUID(value.accountId) || !Array.isArray(value.memberships)) throw invalidResponse();
  return { accountId: value.accountId, memberships: value.memberships.map(membership) };
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

function reservation(value: unknown, uncertain = false): Reservation {
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

  constructor(options: { fetch?: typeof globalThis.fetch; now?: () => number } = {}) {
    this.fetcher = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.now = options.now ?? (() => globalThis.performance.now());
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
   * Primeiro acesso por convite: entra com a senha do provedor e aceita o convite na mesma chamada, para quem ainda
   * não tem conta local. Com sessão iniciada o CSRF anterior deixa de valer. Resultado incerto não é repetido.
   */
  async acceptInvitationWithLogin(input: { email: string; password: string; token: string }, signal?: AbortSignal): Promise<InvitationFirstAccess> {
    if (!/^[A-Za-z0-9_-]{43}$/.test(input.token)) throw new ApiError(404, 'INVITATION_NOT_AVAILABLE', messages.INVITATION_NOT_AVAILABLE!);
    const body = JSON.stringify({ email: input.email, password: input.password, token: input.token });
    return this.auth(async () => {
      try {
        const reply = await this.send('/auth/invitation/accept', { method: 'POST', body, ...(signal ? { signal } : {}) }, this.generation);
        const result = invitationFirstAccess(reply.value);
        this.clearSession();
        return result;
      } catch (error) {
        if (error instanceof ApiError && (error.uncertain || error.code === 'INVALID_API_RESPONSE'))
          throw new ApiError(error.status, 'INVITATION_FIRST_ACCESS_UNKNOWN', messages.INVITATION_FIRST_ACCESS_UNKNOWN!, true);
        throw error;
      }
    });
  }
  async acceptInvitation(token: string, signal?: AbortSignal): Promise<InvitationAccepted> {
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new ApiError(400, 'INVITATION_NOT_AVAILABLE', messages.INVITATION_NOT_AVAILABLE!);
    const reply = await this.request('/v1/invitations/accept', { method: 'POST', body: JSON.stringify({ token }), ...(signal ? { signal } : {}) });
    return invitationAccepted(reply.value);
  }
}

/** Uma chave por intenção. Quem oferece retry deve conservar esta chave e os mesmos dados. */
export function newOperationKey(): string { return `web:${globalThis.crypto.randomUUID()}`; }
export const api = new CondPulseApi();
