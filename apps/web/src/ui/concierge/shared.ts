import type { ManagementScope } from '../management/shared';

export { codeOf, freshKeyCodes, repeatFailure, settleFailure, uncertain, useIntent, usePendingOperation } from '../operationIntent';

/** Chaves de cache da portaria, separadas da gestão e do morador. */
export const conciergeKey = (scope: Pick<ManagementScope, 'accountId' | 'tenantId'>, ...parts: unknown[]) =>
  ['private', scope.accountId, 'tenant', scope.tenantId, 'concierge', ...parts] as const;

export const unitName = (item: { unitLabel?: string; label?: string; blockName: string | null }) =>
  `${item.blockName ? `${item.blockName} · ` : ''}${item.unitLabel ?? item.label ?? ''}`;
