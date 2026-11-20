export type ServerEvent = { event: string; data: string }

/**
 * Reads a text/event-stream (the WHATWG rules the browser's EventSource
 * follows) from chunks of text: `push` returns the events each chunk
 * finished. Comments (the server's keep-alive) and ids are dropped.
 */
export class EventStreamParser {
  private buffer = ''
  private name = ''
  private data: string[] = []
  private lastWasCR = false

  push(chunk: string): ServerEvent[] {
    let text = chunk
    // A \r\n split across two chunks: the \n belongs to the \r already read.
    if (this.lastWasCR && text.startsWith('\n')) text = text.slice(1)
    this.lastWasCR = text.endsWith('\r')
    this.buffer += text
    const lines = this.buffer.split(/\r\n|\r|\n/)
    this.buffer = lines.pop() ?? ''
    const out: ServerEvent[] = []
    for (const line of lines) {
      if (line === '') {
        if (this.data.length) out.push({ event: this.name || 'message', data: this.data.join('\n') })
        this.name = ''
        this.data = []
        continue
      }
      if (line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') this.name = value
      else if (field === 'data') this.data.push(value)
    }
    return out
  }
}

/** Waits between reconnects: 1 s, doubling to 30 s. */
export function reconnectDelay(failures: number): number {
  return Math.min(30_000, 1000 * 2 ** Math.max(0, failures - 1))
}

export type EventStreamOptions = {
  url: string
  token: string
  onEvent(event: ServerEvent): void
  /** The stream is open; `reopened` after a drop, when events may have been missed. */
  onOpen(reopened: boolean): void
  onUnauthorized(): void
  /** The stream dropped (not stopped): the server or the network went away; it reconnects by itself. */
  onDrop?(): void
  /** No byte for this long (the server pings every 15 s) means the link is gone. */
  silenceMs?: number
}

/**
 * GET /api/events read with fetch, since EventSource can't send the bearer
 * header: reconnects with backoff after a drop, gives up on a 401 (the
 * session ended), and can pause while the TV app is in the background.
 */
export class EventStream {
  private controller?: AbortController
  private stopped = true
  private failures = 0
  private dropped = false
  private retry?: ReturnType<typeof setTimeout>

  constructor(private options: EventStreamOptions) {}

  start() {
    if (!this.stopped) return
    this.stopped = false
    void this.run()
  }

  /** Closes the stream; `start` opens it again, as a reconnect. */
  stop() {
    this.stopped = true
    clearTimeout(this.retry)
    this.controller?.abort()
    if (this.controller) this.dropped = true
    this.controller = undefined
  }

  private async run() {
    const { url, token, silenceMs = 45_000 } = this.options
    const controller = new AbortController()
    this.controller = controller
    let watchdog: ReturnType<typeof setTimeout> | undefined
    const quiet = () => { clearTimeout(watchdog); watchdog = setTimeout(() => controller.abort(), silenceMs) }
    let received = false
    try {
      quiet()
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        credentials: 'omit', cache: 'no-store', signal: controller.signal,
      })
      if (response.status === 401) {
        this.stopped = true
        this.options.onUnauthorized()
        return
      }
      if (!response.ok || !response.body) throw new Error('stream refused')
      const reopened = this.dropped
      this.dropped = false
      this.options.onOpen(reopened)
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      const parser = new EventStreamParser()
      for (;;) {
        quiet()
        const { done, value } = await reader.read()
        if (done) break
        if (!received) { received = true; this.failures = 0 }
        for (const event of parser.push(decoder.decode(value, { stream: true }))) {
          if (this.controller !== controller) return
          this.options.onEvent(event)
        }
      }
    } catch { /* Dropped: reconnect below. */ } finally {
      clearTimeout(watchdog)
    }
    if (this.stopped || this.controller !== controller) return
    this.dropped = true
    this.failures++
    this.options.onDrop?.()
    this.retry = setTimeout(() => { if (!this.stopped) void this.run() }, reconnectDelay(this.failures))
  }
}
