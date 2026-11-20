import { afterEach, describe, expect, it, vi } from 'vitest'
import { EventStream, EventStreamParser, reconnectDelay } from './events'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('server events over fetch', () => {
  it('parses events split anywhere across chunks, skipping comments', () => {
    const p = new EventStreamParser()
    expect(p.push(': ping\n\nevent: library.chang')).toEqual([])
    expect(p.push('ed\ndata: {"libraryId":3}\r')).toEqual([])
    expect(p.push('\n\r\nevent:watch.changed\ndata:{}\n')).toEqual([{ event: 'library.changed', data: '{"libraryId":3}' }])
    expect(p.push('\ndata: a\ndata: b\nid: 9\n\n')).toEqual([{ event: 'watch.changed', data: '{}' }, { event: 'message', data: 'a\nb' }])
    expect(p.push('event: x\n\n')).toEqual([])
  })

  it('backs off from a second to half a minute', () => {
    expect([1, 2, 3, 4, 5, 6, 9].map(reconnectDelay)).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000])
  })

  it('sends the bearer header, reconnects after a drop and says the stream reopened', async () => {
    vi.useFakeTimers()
    const requests: Request[] = []
    let opened = 0
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      requests.push(new Request(url, init))
      opened++
      const body = new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(new TextEncoder().encode(`event: rows.changed\ndata: {"n":${opened}}\n\n`))
          if (opened === 1) c.close()
        },
      })
      return new Response(body, { status: 200 })
    })
    const events: string[] = []
    const opens: boolean[] = []
    const stream = new EventStream({ url: 'http://tv.test/api/events', token: 'session-token', onEvent: (e) => events.push(e.data), onOpen: (r) => opens.push(r), onUnauthorized: () => {} })
    stream.start()
    await vi.advanceTimersByTimeAsync(10)
    expect(events).toEqual(['{"n":1}'])
    await vi.advanceTimersByTimeAsync(1000)
    expect(events).toEqual(['{"n":1}', '{"n":2}'])
    expect(opens).toEqual([false, true])
    expect(requests[0].headers.get('Authorization')).toBe('Bearer session-token')
    expect(requests[0].credentials).toBe('omit')
    stream.stop()
  })

  it('stops for good on a 401', async () => {
    const fetcher = vi.fn(async () => new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetcher)
    const unauthorized = vi.fn()
    const stream = new EventStream({ url: 'http://tv.test/api/events', token: 't', onEvent: () => {}, onOpen: () => {}, onUnauthorized: unauthorized })
    stream.start()
    await vi.waitFor(() => expect(unauthorized).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
})
