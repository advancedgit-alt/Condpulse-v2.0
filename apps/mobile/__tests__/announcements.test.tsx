import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { newQueryClient } from '../src/App';
import { ApiError, createApi, type CondPulseApi } from '../src/api/client';
import { openedAnnouncement, unitAnnouncement, type UnitAnnouncement } from '../src/api/announcements';
import { ContractError } from '../src/api/reservations';
import { Announcements } from '../src/ui/Announcements';

const TENANT = '00000000-0000-4000-8000-000000000001';
const UNIT = '20000000-0000-4000-8000-000000000001';
const ACCOUNT = '10000000-0000-4000-8000-000000000001';
const A1 = '52000000-0000-4000-8000-000000000001';
const A2 = '52000000-0000-4000-8000-000000000002';
const item = (overrides: Partial<UnitAnnouncement> = {}): UnitAnnouncement => ({ announcementId: A1, title: 'Manutenção da caixa d’água',
  publishedAt: '2030-01-07T11:00:00Z', openedAt: null, ...overrides });

function fakeApi() {
  const state = { items: [item(), item({ announcementId: A2, title: 'Assembleia fictícia', openedAt: '2030-01-06T12:00:00Z' })], opens: [] as string[],
    listError: null as ApiError | null, openError: null as ApiError | null };
  const api = {
    unitAnnouncements: jest.fn(async () => { if (state.listError) throw state.listError; return { announcements: state.items, truncated: false }; }),
    openAnnouncement: jest.fn(async (_t: string, id: string) => {
      state.opens.push(id);
      if (state.openError) throw state.openError;
      const found = state.items.find((entry) => entry.announcementId === id)!;
      const openedAt = found.openedAt ?? '2030-01-07T11:10:00Z';
      state.items = state.items.map((entry) => entry.announcementId === id ? { ...entry, openedAt } : entry);
      return { announcementId: id, title: found.title, body: 'Linha <b>um</b>\n\nLinha dois', publishedAt: found.publishedAt, openedAt };
    }),
  };
  return { api: api as unknown as CondPulseApi, state };
}
const clients: ReturnType<typeof newQueryClient>[] = [];
afterEach(async () => { await cleanup(); for (const client of clients.splice(0)) client.clear(); });
async function mount(fake = fakeApi()) {
  const queries = newQueryClient(Infinity); clients.push(queries);
  await render(<QueryClientProvider client={queries}>
    <Announcements api={fake.api} accountId={ACCOUNT} tenantId={TENANT} unitId={UNIT} timeZone="America/Sao_Paulo" />
  </QueryClientProvider>);
  return fake;
}
const press = async (name: string) => fireEvent.press(await screen.findByRole('button', { name }));

describe('avisos no celular', () => {
  it('contrato: lista sem texto e abertura do aviso pedido', async () => {
    expect(unitAnnouncement(item()).title).toBe('Manutenção da caixa d’água');
    expect(() => unitAnnouncement({ ...item(), body: 'texto' })).toThrow(ContractError);
    expect(() => openedAnnouncement({ announcementId: A2, title: 'Aviso', body: 'x', publishedAt: '2030-01-07T11:00:00Z', openedAt: '2030-01-07T11:10:00Z' }, A1)).toThrow(ContractError);
    const api = createApi({ baseUrl: 'https://api.example.invalid', tokens: { accessToken: async () => 'synthetic', refresh: async () => null, expire: async () => {} } as never,
      fetch: async () => new Response(JSON.stringify({ code: 'ANNOUNCEMENT_NOT_FOUND', status: 404 }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } }) });
    const error = await api.openAnnouncement(TENANT, A1).then(() => null, (failure: unknown) => failure);
    expect((error as ApiError).code).toBe('ANNOUNCEMENT_NOT_FOUND');
  });

  it('mostra não abertos, abre o texto como texto e registra a abertura uma vez', async () => {
    const fake = await mount();
    expect(await screen.findByText('Avisos · 1 não aberto')).toBeTruthy();
    expect(screen.queryByText(/Linha dois/)).toBeNull();
    expect(screen.getByText(/Abertura não é ciência jurídica/)).toBeTruthy();
    await press('Abrir aviso Manutenção da caixa d’água');
    expect(await screen.findByText('Linha <b>um</b>\n\nLinha dois')).toBeTruthy();
    expect(await screen.findByText('Avisos')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Abrir aviso Manutenção da caixa d’água' })).toBeNull();
    expect(fake.state.opens).toEqual([A1]);
  }, 15000);

  it('aviso arquivado ao abrir mostra a mensagem; falha na lista oferece nova tentativa; função desligada esconde', async () => {
    const fake = fakeApi();
    fake.state.openError = new ApiError(404, 'ANNOUNCEMENT_NOT_FOUND', 'Aviso não encontrado. Ele pode ter sido arquivado pela gestão.', false);
    await mount(fake);
    await press('Abrir aviso Manutenção da caixa d’água');
    expect(await screen.findByText('Aviso não encontrado. Ele pode ter sido arquivado pela gestão.')).toBeTruthy();
    expect(screen.getByText('Avisos · 1 não aberto')).toBeTruthy();
    await cleanup();
    const failing = fakeApi();
    failing.state.listError = new ApiError(503, 'ANNOUNCEMENT_DEPENDENCY_UNAVAILABLE', 'Avisos indisponíveis agora.', false);
    await mount(failing);
    expect(await screen.findByText('Avisos indisponíveis agora.', {}, { timeout: 5000 })).toBeTruthy();
    failing.state.listError = null;
    await press('Tentar consultar avisos de novo');
    expect(await screen.findByText('Avisos · 1 não aberto')).toBeTruthy();
    await cleanup();
    const off = fakeApi();
    off.state.listError = new ApiError(404, 'HTTP_ERROR', 'Indisponível.', false);
    await mount(off);
    await waitFor(() => expect(screen.queryByText(/^Avisos/)).toBeNull());
  }, 20000);
});
