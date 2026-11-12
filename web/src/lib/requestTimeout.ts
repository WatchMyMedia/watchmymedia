/** Bound requests on TVs whose AbortSignal has no static timeout method. */
export async function withRequestTimeout<T>(request: (signal: AbortSignal) => Promise<T>, milliseconds = 10_000): Promise<T> {
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, milliseconds)
  try {
    return await request(controller.signal)
  } catch (error) {
    // Older implementations reject abort() with AbortError. Keep the
    // TimeoutError contract used by the idempotent Live TV start retry.
    if (timedOut) throw new DOMException('The request timed out.', 'TimeoutError')
    throw error
  } finally {
    clearTimeout(timer)
  }
}
