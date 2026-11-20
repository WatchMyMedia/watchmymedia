import { describe, expect, it } from 'vitest'
import { YOUTUBE_EMBED_ORIGIN, type PlayerMessage } from '../../../src/lib/trailers'
import {
  embedUrl, initialTrailer, pausedLine, playerShown, readTrailerMessage, seekTarget, trailerKey, trailerStep, PAUSED, PLAYING, type TrailerEvent, type TrailerState,
} from './trailerPlayer'

const msg = (message: PlayerMessage): TrailerEvent => ({ type: 'message', message })
const run = (s: TrailerState, events: TrailerEvent[], count: number) => events.reduce((x, e) => trailerStep(x, e, count), s)

describe('the trailer screen', () => {
  it('waits out of sight until the player answers, then shows it', () => {
    const s = initialTrailer(0, 2)
    expect(s.phase).toBe('loading')
    expect(playerShown(s)).toBe(false)
    const ready = trailerStep(s, msg({ type: 'ready' }), 2)
    expect(ready.phase).toBe('playing')
    expect(playerShown(ready)).toBe(true)
  })

  it('moves to the next trailer when an owner keeps one on YouTube, then says it can’t play here', () => {
    const second = run(initialTrailer(0, 2), [msg({ type: 'ready' }), msg({ type: 'error', code: 150 })], 2)
    expect(second).toMatchObject({ index: 1, attempt: 1, phase: 'loading', ready: false })
    expect(trailerStep(second, msg({ type: 'error', code: 101 }), 2).phase).toBe('refused')
    expect(initialTrailer(0, 0).phase).toBe('refused')
  })

  it('shows YouTube’s own message for other errors', () => {
    const s = trailerStep(initialTrailer(0, 1), msg({ type: 'error', code: 153 }), 1)
    expect(s.phase).toBe('playing')
    expect(playerShown(s)).toBe(true)
  })

  it('counts a player that never answers as unreachable, and tries again', () => {
    const s = initialTrailer(0, 1)
    expect(trailerStep(s, { type: 'timeout', attempt: 0 }, 1).phase).toBe('unreachable')
    // A wait for an earlier load, or one that answered, changes nothing.
    expect(trailerStep({ ...s, attempt: 2 }, { type: 'timeout', attempt: 1 }, 1).phase).toBe('loading')
    expect(trailerStep(trailerStep(s, msg({ type: 'ready' }), 1), { type: 'timeout', attempt: 0 }, 1).phase).toBe('playing')
    const again = trailerStep(trailerStep(s, { type: 'timeout', attempt: 0 }, 1), { type: 'retry' }, 1)
    expect(again).toMatchObject({ phase: 'loading', attempt: 1 })
    // Word from the player after it was given up on is ignored.
    expect(trailerStep(trailerStep(s, { type: 'timeout', attempt: 0 }, 1), msg({ type: 'ready' }), 1).phase).toBe('unreachable')
  })

  it('puts our pause screen in the player’s place while paused, and the player back to play', () => {
    const playing = run(initialTrailer(0, 1), [msg({ type: 'ready' }), msg({ type: 'state', state: PLAYING })], 1)
    const paused = trailerStep(playing, msg({ type: 'state', state: PAUSED }), 1)
    expect(paused.phase).toBe('paused')
    expect(playerShown(paused)).toBe(false)
    expect(trailerStep(paused, { type: 'resume' }, 1).phase).toBe('playing')
  })

  it('goes back to the movie when a trailer that played ends', () => {
    const ready = trailerStep(initialTrailer(0, 1), msg({ type: 'ready' }), 1)
    expect(trailerStep(ready, msg({ type: 'state', state: 0 }), 1).leave).toBeUndefined()
    const played = trailerStep(ready, msg({ type: 'state', state: PLAYING }), 1)
    expect(trailerStep(played, msg({ type: 'state', state: 0 }), 1).leave).toBe(true)
  })

  it('keeps the time the player reports', () => {
    const s = trailerStep(initialTrailer(0, 1), { type: 'time', timeMs: 27_000, durationMs: 151_000 }, 1)
    expect(s).toMatchObject({ timeMs: 27_000, durationMs: 151_000 })
    expect(trailerStep(s, { type: 'time', timeMs: 30_000 }, 1).durationMs).toBe(151_000)
    expect(pausedLine(27_000, 151_000)).toBe('Paused · 0:27 / 2:31')
    expect(pausedLine(0, 0)).toBe('Paused · 0:00')
  })
})

describe('the remote', () => {
  it('plays, pauses and jumps through YouTube’s API, and leaves ▲ ▼ alone', () => {
    expect(trailerKey('ok', 'playing')).toBe('toggle')
    expect(trailerKey('playPause', 'paused')).toBe('toggle')
    expect(trailerKey('play', 'paused')).toBe('play')
    expect(trailerKey('pause', 'playing')).toBe('pause')
    expect(trailerKey('left', 'playing')).toBe('back10')
    expect(trailerKey('rewind', 'paused')).toBe('back10')
    expect(trailerKey('fastForward', 'playing')).toBe('ahead10')
    expect(trailerKey('up', 'playing')).toBe('swallow')
    expect(trailerKey('down', 'paused')).toBe('swallow')
  })

  it('leaves Return to the screen, and the problem screens’ keys to their buttons', () => {
    expect(trailerKey('back', 'playing')).toBe('pass')
    expect(trailerKey('ok', 'refused')).toBe('pass')
    expect(trailerKey('left', 'unreachable')).toBe('pass')
    expect(trailerKey('ok', 'loading')).toBe('swallow')
  })

  it('jumps within the trailer, short of its end', () => {
    expect(seekTarget(5_000, 151_000, -10_000)).toBe(0)
    expect(seekTarget(145_000, 151_000, 10_000)).toBe(150_000)
    expect(seekTarget(20_000, 0, 10_000)).toBe(30_000)
  })
})

describe('YouTube’s player', () => {
  it('waits to be asked to play, and names this page only when it has an http origin', () => {
    const tv = new URL(embedUrl('abcdefghijk', 'null'))
    expect(tv.origin).toBe(YOUTUBE_EMBED_ORIGIN)
    expect(tv.pathname).toBe('/embed/abcdefghijk')
    expect(tv.searchParams.get('autoplay')).toBe('0')
    expect(tv.searchParams.get('enablejsapi')).toBe('1')
    expect(tv.searchParams.has('origin')).toBe(false)
    expect(new URL(embedUrl('abcdefghijk', 'http://127.0.0.1:5193')).searchParams.get('origin')).toBe('http://127.0.0.1:5193')
  })

  it('reads its events and time, only from its own origin', () => {
    expect(readTrailerMessage(YOUTUBE_EMBED_ORIGIN, 'https://example.com', '{"event":"onReady"}')).toBeNull()
    expect(readTrailerMessage(YOUTUBE_EMBED_ORIGIN, YOUTUBE_EMBED_ORIGIN, '{"event":"onReady"}')?.message).toEqual({ type: 'ready' })
    expect(readTrailerMessage(YOUTUBE_EMBED_ORIGIN, YOUTUBE_EMBED_ORIGIN, '{"event":"onError","info":150}')?.message).toEqual({ type: 'error', code: 150 })
    expect(readTrailerMessage(YOUTUBE_EMBED_ORIGIN, YOUTUBE_EMBED_ORIGIN, '{"event":"infoDelivery","info":{"currentTime":27.4,"duration":151.2,"playerState":1}}'))
      .toEqual({ message: { type: 'state', state: 1 }, timeMs: 27_400, durationMs: 151_200 })
    expect(readTrailerMessage(YOUTUBE_EMBED_ORIGIN, YOUTUBE_EMBED_ORIGIN, 'not json')).toBeNull()
    // The preview's stand-in speaks from the page's own origin.
    expect(readTrailerMessage('http://127.0.0.1:5193', 'http://127.0.0.1:5193', '{"event":"onReady"}')?.message).toEqual({ type: 'ready' })
  })
})
