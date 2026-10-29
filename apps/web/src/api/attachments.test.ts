// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { CondPulseApi } from './client';
import { attachment, attachmentDownload, attachmentUpload, createAttachmentApi, fileProblem, sizeLabel } from './attachments';

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const tenant = '00000000-0000-4000-8000-000000000001';
const request = '96000000-0000-4000-8000-000000000011';
const id = 'a7000000-0000-4000-8000-000000000001';
const item = { attachmentId: id, entityId: request, fileName: 'foto.png', mimeType: 'image/png', sizeBytes: 12, state: 'PENDING',
  rejection: null, mine: true, createdAt: '2030-01-01T12:00:00Z', completedAt: null };
const storage = 'https://abcdefghijklmnopqrst.supabase.co/storage/v1/object';
const upload = { attachment: item, uploadUrl: `${storage}/upload/sign/cp-upload-quarantine/${tenant}/${id}?token=synthetic.token-123456`,
  method: 'PUT', contentType: 'image/png', expiresAt: '2030-01-01T12:15:00Z' };

describe('contrato dos anexos de pedido', () => {
  it('aceita só estados coerentes e esconde pendente ou recusado de quem não enviou', () => {
    expect(attachment(item).state).toBe('PENDING');
    expect(attachment({ ...item, state: 'AVAILABLE', completedAt: '2030-01-01T12:01:00Z', mine: false }).mine).toBe(false);
    expect(() => attachment({ ...item, mine: false })).toThrow();
    expect(() => attachment({ ...item, state: 'REJECTED', completedAt: '2030-01-01T12:01:00Z' })).toThrow();
    expect(() => attachment({ ...item, state: 'AVAILABLE' })).toThrow();
    expect(() => attachment({ ...item, mimeType: 'image/gif' })).toThrow();
    expect(() => attachment({ ...item, fileName: 'x'.repeat(121) })).toThrow();
  });
  it('URL assinada só no formato do Storage, https e com token', () => {
    expect(attachmentUpload(upload).method).toBe('PUT');
    expect(() => attachmentUpload({ ...upload, uploadUrl: upload.uploadUrl.replace('https:', 'http:') })).toThrow();
    expect(() => attachmentUpload({ ...upload, uploadUrl: `${storage}/upload/sign/x?token=` })).toThrow();
    expect(() => attachmentUpload({ ...upload, uploadUrl: 'https://evil.invalid/qualquer?token=synthetic.token-123456' })).toThrow();
    expect(() => attachmentUpload({ ...upload, contentType: 'application/pdf' })).toThrow();
    expect(() => attachmentUpload({ ...upload, attachment: { ...item, state: 'AVAILABLE', completedAt: '2030-01-01T12:01:00Z' } })).toThrow();
    expect(attachmentDownload({ url: `${storage}/sign/cp-upload-quarantine/${tenant}/${id}?token=synthetic.token-123456&download=foto.png`,
      expiresAt: '2030-01-01T12:01:00Z' }).url).toContain('download=foto.png');
    expect(() => attachmentDownload({ url: upload.uploadUrl, expiresAt: '2030-01-01T12:01:00Z' })).toThrow();
  });
  it('confere tipo, tamanho e nome antes de pedir o envio', () => {
    expect(fileProblem({ name: 'foto.png', size: 10, type: 'image/png' })).toBeNull();
    expect(fileProblem({ name: 'laudo.pdf', size: 20 * 1024 * 1024, type: 'application/pdf' })).toBeNull();
    expect(fileProblem({ name: 'foto.png', size: 10 * 1024 * 1024 + 1, type: 'image/png' })).toMatch(/10 MB/);
    expect(fileProblem({ name: 'anim.gif', size: 10, type: 'image/gif' })).toMatch(/JPEG/);
    expect(fileProblem({ name: 'vazio.pdf', size: 0, type: 'application/pdf' })).toMatch(/vazio/);
    expect(fileProblem({ name: 'a"b.png', size: 1, type: 'image/png' })).toMatch(/aspas/);
    expect(sizeLabel(512)).toBe('512 B');
    expect(sizeLabel(2048)).toBe('2 KB');
    expect(sizeLabel(1572864)).toBe('1,5 MB');
  });
  it('reserva pela API com CSRF e envia ao Storage sem credenciais do CondPulse', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' }) : json(upload, 201));
    const storageFetch = vi.fn<typeof globalThis.fetch>(async () => new Response('{}', { status: 200 }));
    const client = createAttachmentApi(new CondPulseApi({ fetch: fetcher }), storageFetch);
    const file = new Blob([new Uint8Array(12)], { type: 'image/png' });
    const reserved = await client.intent(tenant, request, { name: 'foto.png', size: 12, type: 'image/png' });
    const call = fetcher.mock.calls.at(-1)!;
    expect(String(call[0])).toBe(`/v1/tenants/${tenant}/uploads/intents`);
    expect(new Headers(call[1]?.headers).get('X-CSRF-Token')).toBe('synthetic-csrf');
    expect(JSON.parse(String(call[1]?.body))).toEqual({ purpose: 'REQUEST_ATTACHMENT', entityId: request, fileName: 'foto.png', mimeType: 'image/png', sizeBytes: 12 });
    await client.put(reserved, file);
    const put = storageFetch.mock.calls[0]!;
    expect(String(put[0])).toBe(upload.uploadUrl);
    expect(put[1]).toMatchObject({ method: 'PUT', credentials: 'omit', redirect: 'error', headers: { 'Content-Type': 'image/png' } });
    storageFetch.mockResolvedValueOnce(new Response('', { status: 413 }));
    await expect(client.put(reserved, file)).rejects.toMatchObject({ code: 'ATTACHMENT_UPLOAD_FAILED' });
    storageFetch.mockRejectedValueOnce(new TypeError('rede'));
    await expect(client.put(reserved, file)).rejects.toMatchObject({ code: 'ATTACHMENT_UPLOAD_FAILED', uncertain: true });
  });
  it('problemas do servidor viram mensagens conhecidas', async () => {
    const fetcher = vi.fn<typeof globalThis.fetch>();
    fetcher.mockImplementation(async (input) => String(input) === '/auth/csrf' ? json({ headerName: 'X-CSRF-Token', token: 'synthetic-csrf' })
      : json({ code: 'ATTACHMENT_NOT_FOUND', status: 404 }, 404));
    const client = createAttachmentApi(new CondPulseApi({ fetch: fetcher }));
    await expect(client.download(tenant, id)).rejects.toMatchObject({ code: 'ATTACHMENT_NOT_FOUND', message: 'Arquivo não encontrado.' });
    fetcher.mockImplementation(async () => json({ attachments: [{ ...item, mine: false }] }));
    await expect(client.forRequest(tenant, request)).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });
});
