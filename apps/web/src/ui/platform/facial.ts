import { isUUID } from '../../api/client';
import { capabilityNames, isApproval, limitNames, type AccessHomologation, type CapabilityName, type CapabilityObservation, type CreateHomologationBody,
  type Decision, type DecideBody, type LimitName, type OperatingMode, type Topology } from '../../api/accessCatalog';
import { displayText } from '../../api/text';

/** Regras dos formulários do acesso facial, iguais às do servidor (CatalogService.validate/decide); o servidor decide. */
export type TriState = '' | 'yes' | 'no';
export const triValue = (value: TriState): boolean | null => (value === '' ? null : value === 'yes');
export const triLabel = (value: boolean | null | undefined) => (value === true ? 'Sim' : value === false ? 'Não' : 'Desconhecido');

/** Um identificador (UUID) por linha, sem repetição; linhas vazias ignoradas. */
export function uuidLines(text: string, max = 300): { values: string[] } | { error: string } {
  const lines = text.split(/\r?\n/).map((line) => line.trim().toLowerCase()).filter(Boolean);
  for (const line of lines) {
    if (!isUUID(line)) return { error: `"${String(line).slice(0, 40)}" não é um identificador de evidência (UUID).` };
  }
  if (new Set(lines).size !== lines.length) return { error: 'Há referência de evidência repetida.' };
  if (lines.length > max) return { error: `Use até ${max} referências de evidência.` };
  return { values: lines };
}
/** Um texto por linha (limitação ou restrição), de 1 a 500 caracteres visíveis, até 100. */
export function textLines(text: string, label: string): { values: string[] } | { error: string } {
  const values: string[] = [];
  for (const [index, line] of text.split(/\r?\n/).entries()) {
    if (!line.trim()) continue;
    const value = displayText(line, 500);
    if (value === null) return { error: `${label}, linha ${index + 1}: use de 1 a 500 caracteres visíveis.` };
    values.push(value);
  }
  if (values.length > 100) return { error: `${label}: até 100 linhas.` };
  return { values };
}
function reasonOf(text: string): string | null {
  const value = displayText(text, 1000);
  return value !== null && value.length >= 8 ? value : null;
}

export interface DossierDraft {
  modelProfileId: string; firmwareVersion: string; adapterName: string; adapterVersion: string; apiContractVersion: string;
  operatingMode: OperatingMode | ''; topology: Topology | ''; capabilities: Record<CapabilityName, { documented: TriState; observed: TriState }>;
  limits: Record<LimitName, string>; evidence: string; limitations: string; reason: string;
}
export const emptyDossier = (modelProfileId = ''): DossierDraft => ({ modelProfileId, firmwareVersion: '', adapterName: '', adapterVersion: '', apiContractVersion: '',
  operatingMode: '', topology: '',
  capabilities: Object.fromEntries(capabilityNames.map((name) => [name, { documented: '', observed: '' }])) as DossierDraft['capabilities'],
  limits: Object.fromEntries(limitNames.map((name) => [name, ''])) as DossierDraft['limits'], evidence: '', limitations: '', reason: '' });

export function dossierBody(homologationId: string, draft: DossierDraft): CreateHomologationBody | string {
  if (!isUUID(draft.modelProfileId)) return 'Escolha o perfil do catálogo.';
  const firmwareVersion = draft.firmwareVersion.trim();
  if (!/^[A-Za-z0-9._+()-]{1,128}$/.test(firmwareVersion)) return 'Firmware exato: letras, números e . _ + ( ) -, até 128 caracteres, sem curinga.';
  const adapterName = draft.adapterName.trim();
  if (!/^[a-z0-9][a-z0-9._-]{1,63}$/.test(adapterName)) return 'Adaptador: minúsculas, números e . _ -, de 2 a 64 caracteres.';
  const adapterVersion = draft.adapterVersion.trim();
  if (!/^[A-Za-z0-9._+-]{1,64}$/.test(adapterVersion)) return 'Versão do adaptador: letras, números e . _ + -, até 64 caracteres.';
  const apiContractVersion = draft.apiContractVersion.trim();
  if (!/^[A-Za-z0-9._-]{1,32}$/.test(apiContractVersion)) return 'Versão do contrato da API: letras, números e . _ -, até 32 caracteres.';
  if (!draft.operatingMode) return 'Escolha o modo de operação.';
  if (!draft.topology) return 'Escolha a topologia.';
  const capabilities = Object.fromEntries(capabilityNames.map((name) => [name, { documented: triValue(draft.capabilities[name].documented),
    observed: triValue(draft.capabilities[name].observed) } satisfies CapabilityObservation])) as Record<CapabilityName, CapabilityObservation>;
  const limits = {} as Record<LimitName, number | null>;
  for (const name of limitNames) {
    const text = draft.limits[name].trim();
    if (text === '') { limits[name] = null; continue; }
    if (!/^\d{1,10}$/.test(text) || Number(text) > 2147483647) return 'Limites: números inteiros não negativos (em branco = desconhecido, nunca ilimitado).';
    limits[name] = Number(text);
  }
  const evidence = uuidLines(draft.evidence);
  if ('error' in evidence) return evidence.error;
  const limitations = textLines(draft.limitations, 'Limitações');
  if ('error' in limitations) return limitations.error;
  const reason = reasonOf(draft.reason);
  if (!reason) return 'Informe o motivo (8 a 1000 caracteres).';
  return { homologationId, modelProfileId: draft.modelProfileId.toLowerCase(), firmwareVersion, adapterName, adapterVersion, apiContractVersion,
    operatingMode: draft.operatingMode, topology: draft.topology, capabilities, limits, evidenceRefs: evidence.values, limitations: limitations.values, reason };
}

export interface DecisionDraft { decision: Decision | ''; reason: string; evidence: string; approved: CapabilityName[]; reviewDue: string; restrictions: string }
export const emptyDecision = (): DecisionDraft => ({ decision: '', reason: '', evidence: '', approved: [], reviewDue: '', restrictions: '' });
/** Capacidades que podem ser aprovadas: só as observadas no dossiê. */
export const approvable = (homologation: AccessHomologation) => capabilityNames.filter((name) => homologation.capabilities[name]?.observed === true);

export function decisionBody(decisionId: string, draft: DecisionDraft, homologation: AccessHomologation, now = Date.now()): DecideBody | string {
  if (!draft.decision) return 'Escolha a decisão.';
  const approval = isApproval(draft.decision);
  const reason = reasonOf(draft.reason);
  if (!reason) return 'Informe o motivo (8 a 1000 caracteres).';
  const evidence = uuidLines(draft.evidence);
  if ('error' in evidence) return evidence.error;
  if (approval && evidence.values.length === 0) return 'Aprovação exige ao menos uma referência de evidência de execução própria.';
  const allowed = new Set(approvable(homologation));
  if (!approval && draft.approved.length > 0) return 'Só aprovação lista capacidades.';
  if (draft.approved.some((name) => !allowed.has(name))) return 'Só capacidade observada no dossiê pode ser aprovada.';
  let reviewDueAt: string | null = null;
  if (draft.reviewDue) {
    if (!approval) return 'A data de revisão só vale em aprovação.';
    const at = Date.parse(draft.reviewDue);
    if (!Number.isFinite(at) || at <= now) return 'A revisão precisa ser futura.';
    reviewDueAt = new Date(at).toISOString();
  }
  const restrictions = textLines(draft.restrictions, 'Restrições');
  if ('error' in restrictions) return restrictions.error;
  if (!approval && restrictions.values.length > 0) return 'Restrições só em aprovação.';
  return { decisionId, decision: draft.decision, reason, evidenceRefs: evidence.values, approvedCapabilityNames: [...draft.approved].sort(),
    reviewDueAt, restrictions: restrictions.values, expectedVersion: homologation.version };
}

/** Só endereços https viram link; o resto aparece como texto. */
export function safeLink(url: string | null): string | null {
  if (!url) return null;
  try { const parsed = new URL(url); return parsed.protocol === 'https:' && !parsed.username && !parsed.password ? parsed.href : null; }
  catch { return null; }
}
