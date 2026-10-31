// Dados sintéticos para testes unitários do catálogo facial; não entram no bundle (nenhum módulo da aplicação importa).
const H = 'a'.repeat(64);
export const profileFixture = (overrides: Record<string, unknown> = {}) => ({ id: '30000000-0000-4000-8000-0000000000f1', catalogCode: 'CI-M01', manufacturer: 'CONTROL_ID',
  sku: 'iDFace Lite e Pro', hardwareRevision: '', region: 'BR', category: 'FACE_TERMINAL', sourceCategory: 'TERMINAL_FACIAL', lifecycle: 'DOCUMENTED_CANDIDATE',
  status: 'CANDIDATE', revision: 1, sources: [{ id: 'CI01', title: 'Control iD iDFace', url: 'https://www.controlid.com.br/', consultedOn: '2026-10-30' }],
  declaredCapabilities: { interfaces: ['REST'], ingressProtection: 'IP65', homologatedFirmware: null }, declaredLimits: { facesPublished: 3000 },
  notes: 'Nota sintética.', consultedOn: '2026-10-30', documentHash: H, ...overrides });
export const caps = (observed: boolean | null) => Object.fromEntries(['doorContactEvents', 'eventBackfill', 'faceDeletion', 'faceEnrollment', 'localValidity',
  'offlineRestriction', 'onlineDecisionBeforeRelay', 'passageEvents', 'perDoorRights', 'safeRecovery', 'scopedRemoteOpen', 'secureTransport']
  .map((name) => [name, { documented: true, observed, approved: null }]));
export const homologationFixture = (overrides: Record<string, unknown> = {}) => ({ id: '40000000-0000-4000-8000-0000000000f1', revision: 1,
  modelProfileId: '30000000-0000-4000-8000-0000000000f1', firmwareVersion: '1.2.3', adapterName: 'controlid-rest', adapterVersion: '0.1.0', apiContractVersion: 'v1',
  operatingMode: 'EDGE_ONLINE', topology: 'LOCAL_GATEWAY', capabilities: caps(true), limits: { faces: 3000, users: null }, limitations: [], evidenceRefs: [],
  evidenceHash: H, status: 'CANDIDATO_DOCUMENTADO', approvedCapabilities: [], restrictions: [], approvedAt: null, reviewDueAt: null, current: false, version: 0, ...overrides });

