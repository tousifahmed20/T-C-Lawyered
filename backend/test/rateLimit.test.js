import { describe, it, expect, beforeEach } from 'vitest';
import { allowWrite, _resetRateLimit } from '../src/middleware/rateLimit.js';

describe('write rate limiter', () => {
  beforeEach(() => _resetRateLimit());

  it('allows up to the limit then blocks', () => {
    const limit = 3;
    expect(allowWrite('1.1.1.1', limit)).toBe(true);
    expect(allowWrite('1.1.1.1', limit)).toBe(true);
    expect(allowWrite('1.1.1.1', limit)).toBe(true);
    expect(allowWrite('1.1.1.1', limit)).toBe(false);
  });

  it('tracks IPs independently', () => {
    const limit = 1;
    expect(allowWrite('1.1.1.1', limit)).toBe(true);
    expect(allowWrite('1.1.1.1', limit)).toBe(false);
    expect(allowWrite('2.2.2.2', limit)).toBe(true);
  });
});
