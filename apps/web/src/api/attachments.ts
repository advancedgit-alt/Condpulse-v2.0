import { api, ApiError, identifier, invalidResponse, isDate, isInteger, isString, isUUID, list, oneOf, record, type CondPulseApi } from './client';
import type { ISODateTime, UUID } from './types';

/**
 * Anexos privados de pedido (CP-017). O arquivo vai direto ao Storage privado por URL assinada curta emitida pela API;
 * só fica disponível depois que a API confere tamanho e conteúdo. Download sempre passa pela API e recebe link de
 * 60 segundos. Toda resposta é validada antes da interface; URLs só são aceitas no formato esperado do Storage.
 */
export const attachmentTypes = ['image/jpeg', 'image/png', 'application/pdf'] as const;
export type AttachmentType = typeof attachmentTypes[number];
export type AttachmentState = 'PENDING' | 'AVAILABLE' | 'REJECTED';
export type AttachmentRejection = 'SIZE_MISMATCH' | 'TYPE_MISMATCH' | 'NOT_UPLOADED' | 'UPLOAD_EXPIRED';
export interface Attachment {
  attachmentId: UUID; entityId: UUID; fileName: string; mimeType: AttachmentType; sizeBytes: number; state: AttachmentState;
  rejection: AttachmentRejection | null; mine: boolean; createdAt: ISODateTime; completedAt: ISODateTime | null;
}
export interface AttachmentUpload { attachment: Attachment; uploadUrl: string; method: 'PUT'; contentType: AttachmentType; expiresAt: ISODateTime }
export interface AttachmentDownload { url: string; expiresAt: ISODateTime }

export const IMAGE_LIMIT = 10 * 1024 * 1024;
export const PDF_LIMIT = 20 * 1024 * 1024;
export const ATTACHMENTS_PER_REQUEST = 10;

/** Problema local antes de pedir o envio: tipo pelo navegador só filtra; o servidor confere o conteúdo de verdade. */
export function fileProblem(file: { name: string; size: number; type: string }): string | null {
  if (!oneOf(file.type, attachmentTypes)) return 'Envie foto JPEG ou PNG ou documento PDF.';
  if (file.size < 1) return 'O arquivo está vazio.';
  if (file.size > (file.type === 'application/pdf' ? PDF_LIMIT : IMAGE_LIMIT)) return 'Fotos até 10 MB e PDFs até 20 MB.';
  const name = file.name.replace(/\\/g, '/').split('/').pop()!.trim();
  if (name.length < 1 || [...name].length > 120 || /["\p{Cc}]/u.test(name)) return 'Renomeie o arquivo para até 120 caracteres, sem aspas.';
  return null;
}
export function sizeLabel(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} MB`;
}

const signedUrl = (value: unknown, kind: 'upload/sign' | 'sign'): value is string => {
  if (!isString(value) || value.length > 8192) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.username === '' && url.password === '' && url.hash === ''
      && url.pathname.startsWith(`/storage/v1/object/${kind}/`) && /^[A-Za-z0-9._~-]{16,4096}$/.test(url.searchParams.get('token') ?? '');
  } catch { return false; }
};

export function attachment(value: unknown, uncertain = false): Attachment {
  if (!record(value) || !isUUID(value.attachmentId) || !isUUID(value.entityId) || !isString(value.fileName) || value.fileName.length < 1
    || [...value.fileName].length > 120 || !oneOf(value.mimeType, attachmentTypes) || !isInteger(value.sizeBytes) || value.sizeBytes < 1
    || !oneOf(value.state, ['PENDING', 'AVAILABLE', 'REJECTED'] as const) || typeof value.mine !== 'boolean' || !isDate(value.createdAt)
    || !(value.completedAt == null || isDate(value.completedAt))
    || !(value.rejection == null || oneOf(value.rejection, ['SIZE_MISMATCH', 'TYPE_MISMATCH', 'NOT_UPLOADED', 'UPLOAD_EXPIRED'] as const))
    || (value.state === 'REJECTED') !== (value.rejection != null) || (value.state === 'PENDING') !== (value.completedAt == null)
    // Pendente e recusado só aparecem para quem enviou.
    || (value.state !== 'AVAILABLE' && !value.mine)) throw invalidResponse(uncertain);
  return { attachmentId: value.attachmentId, entityId: value.entityId, fileName: value.fileName, mimeType: value.mimeType,
    sizeBytes: value.sizeBytes, state: value.state, rejection: (value.rejection ?? null) as AttachmentRejection | null, mine: value.mine,
    createdAt: value.createdAt, completedAt: (value.completedAt ?? null) as string | null };
}
export function attachmentUpload(value: unknown): AttachmentUpload {
  if (!record(value) || !signedUrl(value.uploadUrl, 'upload/sign') || value.method !== 'PUT' || !oneOf(value.contentType, attachmentTypes)
    || !isDate(value.expiresAt)) throw invalidResponse(true);
  const item = attachment(value.attachment, true);
  if (item.state !== 'PENDING' || !item.mine || item.mimeType !== value.contentType) throw invalidResponse(true);
  return { attachment: item, uploadUrl: value.uploadUrl, method: 'PUT', contentType: value.contentType, expiresAt: value.expiresAt };
}
export function attachmentDownload(value: unknown): AttachmentDownload {
  if (!record(value) || !signedUrl(value.url, 'sign') || !isDate(value.expiresAt)) throw invalidResponse();
  return { url: value.url, expiresAt: value.expiresAt };
}

export function createAttachmentApi(client: CondPulseApi = api, fetcher: typeof globalThis.fetch = (...args) => globalThis.fetch(...args)) {
  const tenant = (tenantId: string) => `/v1/tenants/${identifier(tenantId)}`;
  return {
    forRequest: async (tenantId: string, requestId: string, signal?: AbortSignal) =>
      client.call(`${tenant(tenantId)}/requests/${identifier(requestId)}/attachments`, (value) => {
        if (!record(value) || !Array.isArray(value.attachments) || value.attachments.length > 50) throw invalidResponse();
        return list(value.attachments, (item) => attachment(item));
      }, signal ? { signal } : {}),
    intent: async (tenantId: string, requestId: string, file: { name: string; size: number; type: string }) =>
      client.call(`${tenant(tenantId)}/uploads/intents`, (value) => attachmentUpload(value), { method: 'POST',
        body: { purpose: 'REQUEST_ATTACHMENT', entityId: requestId, fileName: file.name, mimeType: file.type, sizeBytes: file.size } }),
    /** Envio direto ao Storage pela URL assinada: sem cookies nem credenciais do CondPulse. */
    put: async (upload: AttachmentUpload, file: Blob, signal?: AbortSignal): Promise<void> => {
      let response: Response;
      try {
        response = await fetcher(upload.uploadUrl, { method: 'PUT', body: file, headers: { 'Content-Type': upload.contentType },
          credentials: 'omit', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer', ...(signal ? { signal } : {}) });
      } catch {
        throw new ApiError(0, 'ATTACHMENT_UPLOAD_FAILED', 'O envio do arquivo não foi concluído. Confira a conexão e tente de novo.', true);
      }
      if (!response.ok) throw new ApiError(response.status, 'ATTACHMENT_UPLOAD_FAILED',
        response.status === 413 ? 'O armazenamento recusou o tamanho do arquivo.' : 'O armazenamento recusou o arquivo. Tente de novo.');
    },
    complete: async (tenantId: string, attachmentId: string) =>
      client.call(`${tenant(tenantId)}/attachments/${identifier(attachmentId)}/complete`, (value) => attachment(value, true), { method: 'POST' }),
    download: async (tenantId: string, attachmentId: string) =>
      client.call(`${tenant(tenantId)}/attachments/${identifier(attachmentId)}/download`, (value) => attachmentDownload(value), { method: 'POST' }),
  };
}
export const attachments = createAttachmentApi();
