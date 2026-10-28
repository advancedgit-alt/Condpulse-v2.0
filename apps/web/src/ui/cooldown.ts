import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { remainingCooldown } from '../api/cooldown';

export function useCooldown(error: unknown): number {
  const deadline = error instanceof ApiError ? error.retryUntil : undefined;
  const [, tick] = useState(0);
  useEffect(() => {
    if (remainingCooldown(deadline) === 0) return;
    const interval = window.setInterval(() => {
      tick(value => value + 1);
      if (remainingCooldown(deadline) === 0) window.clearInterval(interval);
    }, 250);
    return () => window.clearInterval(interval);
  }, [deadline]);
  return remainingCooldown(deadline);
}
