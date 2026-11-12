import { afterEach, expect, it, vi } from 'vitest'
import { withRequestTimeout } from './requestTimeout'

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

it('clears deadlines after success or failure without requiring the static AbortSignal API', async () => {
  vi.useFakeTimers()
  vi.spyOn(AbortSignal, 'timeout').mockImplementation(() => { throw new TypeError('Unavailable on this TV') })
  await expect(withRequestTimeout(async (signal) => { expect(signal.aborted).toBe(false); return 'ready' })).resolves.toBe('ready')
  const failure = new TypeError('Network unavailable')
  await expect(withRequestTimeout(async () => { throw failure })).rejects.toBe(failure)
  expect(vi.getTimerCount()).toBe(0)
})

it('aborts a stuck request and retains the TimeoutError retry contract', async () => {
  vi.useFakeTimers()
  const pending = withRequestTimeout((signal) => new Promise((_, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  }))
  const rejected = expect(pending).rejects.toMatchObject({ name: 'TimeoutError' })
  await vi.advanceTimersByTimeAsync(10_000)
  await rejected
  expect(vi.getTimerCount()).toBe(0)
})
