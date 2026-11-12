import { describe, expect, it } from 'vitest'
import { afterPlayerError, commandMessage, embedUrl, firstTrailer, listeningMessage, readPlayerMessage, watchUrl, youTubeTrailers, type MovieTrailer } from './trailers'

const yt = (key: string, name = 'Official Trailer'): MovieTrailer => ({ source: 'youtube', key, name })
const file = (fileId: number, name = 'Trailer'): MovieTrailer => ({ source: 'file', fileId, name, durationMs: 150_000 })

describe('firstTrailer', () => {
  it('plays the first trailer the server lists', () => {
    expect(firstTrailer([file(7), yt('abcdefghijk')])).toEqual({ kind: 'file', fileId: 7, name: 'Trailer' })
    expect(firstTrailer([yt('abcdefghijk'), file(7)])).toEqual({ kind: 'youtube', key: 'abcdefghijk', name: 'Official Trailer' })
  })

  it('skips sources it does not know and entries it cannot play', () => {
    expect(firstTrailer([{ source: 'vimeo', key: '123', name: 'Trailer' }, file(0), yt('bad key/..'), yt('abcdefghijk')])).toEqual({
      kind: 'youtube',
      key: 'abcdefghijk',
      name: 'Official Trailer',
    })
  })

  it('has nothing to play without trailers, as from an older server', () => {
    expect(firstTrailer(undefined)).toBeNull()
    expect(firstTrailer([])).toBeNull()
    expect(firstTrailer([{ source: 'vimeo', key: '123', name: 'Trailer' }])).toBeNull()
  })
})

describe('youTubeTrailers', () => {
  it('keeps the YouTube trailers in order and leaves out files', () => {
    expect(youTubeTrailers([file(7), yt('aaaaaaaaaaa', 'Official Trailer'), yt('bbbbbbbbbbb', 'Teaser')]).map((t) => t.key)).toEqual(['aaaaaaaaaaa', 'bbbbbbbbbbb'])
  })
})

describe('embedUrl', () => {
  it('frames the privacy-enhanced player with its events posted to this page', () => {
    const u = new URL(embedUrl('dQw4w9WgXcQ', 'http://127.0.0.1:27600'))
    expect(u.origin).toBe('https://www.youtube-nocookie.com')
    expect(u.pathname).toBe('/embed/dQw4w9WgXcQ')
    expect(Object.fromEntries(u.searchParams)).toEqual({ autoplay: '1', rel: '0', playsinline: '1', enablejsapi: '1', origin: 'http://127.0.0.1:27600' })
  })

  it('links to the video on YouTube', () => {
    expect(watchUrl('dQw4w9WgXcQ')).toBe('https://www.youtube.com/watch?v=dQw4w9WgXcQ')
  })
})

describe('readPlayerMessage', () => {
  const from = 'https://www.youtube-nocookie.com'

  it('reads the player events the page acts on', () => {
    expect(readPlayerMessage(from, '{"event":"onReady","info":null,"id":1,"channel":"widget"}')).toEqual({ type: 'ready' })
    expect(readPlayerMessage(from, '{"event":"onError","info":150,"id":1,"channel":"widget"}')).toEqual({ type: 'error', code: 150 })
    expect(readPlayerMessage(from, '{"event":"onStateChange","info":0,"id":1,"channel":"widget"}')).toEqual({ type: 'state', state: 0 })
    expect(readPlayerMessage(from, '{"event":"infoDelivery","info":{"playerState":1,"currentTime":3.2},"id":1,"channel":"widget"}')).toEqual({ type: 'state', state: 1 })
    expect(readPlayerMessage(from, '{"event":"initialDelivery","info":{"playerState":-1},"id":1,"channel":"widget"}')).toEqual({ type: 'other' })
  })

  it('ignores every other origin, youtube.com included', () => {
    const msg = '{"event":"onError","info":150}'
    expect(readPlayerMessage('https://www.youtube.com', msg)).toBeNull()
    expect(readPlayerMessage('https://evil.example', msg)).toBeNull()
    expect(readPlayerMessage('http://www.youtube-nocookie.com', msg)).toBeNull()
  })

  it('ignores what it cannot read', () => {
    expect(readPlayerMessage(from, 'not json')).toBeNull()
    expect(readPlayerMessage(from, '42')).toBeNull()
    expect(readPlayerMessage(from, null)).toBeNull()
    expect(readPlayerMessage(from, { info: 150 })).toBeNull()
    expect(readPlayerMessage(from, { event: 'onError', info: '150' })).toEqual({ type: 'other' })
  })
})

describe('afterPlayerError', () => {
  it('moves to the next trailer when the owner keeps a video on YouTube, then gives up', () => {
    expect(afterPlayerError(150, 0, 3)).toEqual({ next: 1 })
    expect(afterPlayerError(101, 1, 3)).toEqual({ next: 2 })
    expect(afterPlayerError(150, 2, 3)).toBe('refused')
    expect(afterPlayerError(101, 0, 1)).toBe('refused')
  })

  it('skips a video that is gone, but sends nobody to YouTube for it', () => {
    expect(afterPlayerError(100, 0, 2)).toEqual({ next: 1 })
    expect(afterPlayerError(100, 1, 2)).toBe('stay')
  })

  it("leaves other errors to YouTube's own message", () => {
    expect(afterPlayerError(2, 0, 3)).toBe('stay')
    expect(afterPlayerError(5, 0, 3)).toBe('stay')
    expect(afterPlayerError(153, 0, 3)).toBe('stay')
  })
})

describe('messages to the player', () => {
  it('speaks the IFrame API script’s format', () => {
    expect(JSON.parse(listeningMessage(1))).toEqual({ event: 'listening', id: 1, channel: 'widget' })
    expect(JSON.parse(commandMessage(1, 'pauseVideo'))).toEqual({ event: 'command', func: 'pauseVideo', args: [], id: 1, channel: 'widget' })
    expect(JSON.parse(commandMessage(1, 'addEventListener', ['onError']))).toEqual({ event: 'command', func: 'addEventListener', args: ['onError'], id: 1, channel: 'widget' })
  })
})
