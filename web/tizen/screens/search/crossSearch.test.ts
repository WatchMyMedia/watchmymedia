import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../src/api/schema'
import { ASK_AGAIN_MS, CrossSearch, CURRENT, mergeResults, noneAnswered, resultKey, searchLines, SERVER_MS, SLOW_MS, type Asked, type Part } from './crossSearch'
import { askingAgain, didNotAnswer, emptyWords, leftOutLine, noMatchWords, stillAsking } from './searchResults'

type Results = components['schemas']['SearchResults']

const movie = (id: number, title: string) => ({ id, title }) as Results['movies'][number]
const show = (id: number, title: string) => ({ id, title }) as Results['shows'][number]
const results = (movies: Results['movies'] = [], shows: Results['shows'] = []): Results => ({ movies, shows, episodes: [] })

/** A server whose answer the test gives when it wants to, and which records what it was asked. */
function server(id: string, name: string) {
  const asked: { q: string; signal: AbortSignal; resolve(r: Results): void; reject(e: unknown): void }[] = []
  const it: Asked = { id, name, search: (q, signal) => new Promise<Results>((resolve, reject) => { asked.push({ q, signal, resolve, reject }) }) }
  return { ...it, asked }
}

const titles = (parts: readonly Part[], current: Results | undefined, done = true) => mergeResults(current, done, parts).movies.map((t) => t.server ? `${t.item.title} (${t.server.name})` : t.item.title)
const flush = () => vi.advanceTimersByTimeAsync(0)

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

describe('search across servers', () => {
  it('draws the current server’s titles first, then each other server’s in Servers’ order as they arrive', async () => {
    const cabin = server('cabin', 'Cabin server'), den = server('den', 'Den server')
    const cross = new CrossSearch([cabin, den])
    const mine = results([movie(1, 'Harbor Nights'), movie(2, 'The Harbor Choir')])
    cross.search('harbor')
    expect(cabin.asked.map((a) => a.q)).toEqual(['harbor'])
    expect(den.asked.map((a) => a.q)).toEqual(['harbor'])
    // The others wait for the current server's, which draw as today.
    expect(titles(cross.snapshot(), undefined, false)).toEqual([])
    expect(titles(cross.snapshot(), mine)).toEqual(['Harbor Nights', 'The Harbor Choir'])

    // Den answers first, and waits its turn behind Cabin: nothing on screen moves.
    den.asked[0].resolve(results([movie(1, 'Harbor Road')]))
    await flush()
    expect(titles(cross.snapshot(), mine)).toEqual(['Harbor Nights', 'The Harbor Choir'])
    cabin.asked[0].resolve(results([movie(1, 'Harbor Nights')]))
    await flush()
    // The same title on two servers is listed twice, the other one named.
    expect(titles(cross.snapshot(), mine)).toEqual(['Harbor Nights', 'The Harbor Choir', 'Harbor Nights (Cabin server)', 'Harbor Road (Den server)'])
    const merged = mergeResults(mine, true, cross.snapshot())
    expect(merged).toMatchObject({ settled: true, answered: 3, asking: [], failed: [] })
    // Ids are each server's own, so a card's key carries its server.
    expect(merged.movies.map(resultKey)).toEqual(['1', '2', 'cabin:1', 'den:1'])
    cross.stop()
  })

  it('says which server it is still asking after a second, and adds its titles when it answers', async () => {
    const cabin = server('cabin', 'Cabin server')
    const cross = new CrossSearch([cabin])
    const mine = results([], [show(1, 'Harbor Lights')])
    cross.search('harbor')
    await vi.advanceTimersByTimeAsync(SLOW_MS - 1)
    expect(mergeResults(mine, true, cross.snapshot())).toMatchObject({ asking: [], settled: false })
    await vi.advanceTimersByTimeAsync(1)
    expect(mergeResults(mine, true, cross.snapshot())).toMatchObject({ asking: ['Cabin server'], settled: false })
    cabin.asked[0].resolve(results([], [show(4, 'Harborside')]))
    await flush()
    const merged = mergeResults(mine, true, cross.snapshot())
    expect(merged.shows.map((t) => [t.item.title, t.server?.name])).toEqual([['Harbor Lights', undefined], ['Harborside', 'Cabin server']])
    expect(merged).toMatchObject({ asking: [], settled: true })
    cross.stop()
  })

  it('gives a server four seconds, then lists the rest without it and doesn’t ask it again for a while', async () => {
    const cabin = server('cabin', 'Cabin server'), den = server('den', 'Den server')
    const cross = new CrossSearch([den, cabin])
    const mine = results([movie(1, 'Harbor Nights')])
    cross.search('harbor')
    cabin.asked[0].resolve(results([movie(9, 'Harbour Run')]))
    await flush()
    // Cabin waits behind Den, which is first in Servers and still out.
    expect(titles(cross.snapshot(), mine)).toEqual(['Harbor Nights'])
    await vi.advanceTimersByTimeAsync(SERVER_MS)
    expect(den.asked[0].signal.aborted).toBe(true)
    const merged = mergeResults(mine, true, cross.snapshot())
    expect(merged).toMatchObject({ failed: [{ id: 'den', name: 'Den server' }], asking: [], settled: true, answered: 2 })
    expect(titles(cross.snapshot(), mine)).toEqual(['Harbor Nights', 'Harbour Run (Cabin server)'])

    // More typing asks Cabin, not Den: it failed a moment ago, and its line stays.
    cross.search('harbour')
    expect(cabin.asked.map((a) => a.q)).toEqual(['harbor', 'harbour'])
    expect(den.asked).toHaveLength(1)
    expect(mergeResults(mine, true, cross.snapshot()).failed).toEqual([{ id: 'den', name: 'Den server' }])
    // Try again asks it now.
    cross.retry('den')
    expect(den.asked.map((a) => a.q)).toEqual(['harbor', 'harbour'])
    den.asked[1].reject(new TypeError('Failed to fetch'))
    await flush()
    expect(mergeResults(mine, true, cross.snapshot()).failed).toEqual([{ id: 'den', name: 'Den server' }])
    // And once the wait is over, the next words ask it again.
    await vi.advanceTimersByTimeAsync(ASK_AGAIN_MS)
    cross.search('harbour r')
    expect(den.asked.map((a) => a.q)).toEqual(['harbor', 'harbour', 'harbour r'])
    cross.stop()
  })

  it('cancels every call still out on a keystroke, and takes no answer from one', async () => {
    const cabin = server('cabin', 'Cabin server'), den = server('den', 'Den server')
    const cross = new CrossSearch([cabin, den])
    const seen: number[] = []
    cross.subscribe(() => seen.push(cross.snapshot().length))
    cross.search('har')
    cabin.asked[0].resolve(results([movie(1, 'Harvest')]))
    await flush()
    cross.cancel()
    expect(cabin.asked[0].signal.aborted).toBe(false)
    expect(den.asked[0].signal.aborted).toBe(true)
    // An answer to cancelled words changes nothing, and a cancelled call doesn't count as a failure.
    den.asked[0].reject(new DOMException('Cancelled', 'AbortError'))
    await flush()
    expect(cross.snapshot().find((p) => p.id === 'den')).toMatchObject({ state: 'asking' })

    cross.search('harb')
    expect(den.asked.map((a) => a.q)).toEqual(['har', 'harb'])
    // Until Cabin's new answer is in, its last one stays where it was on screen.
    expect(titles(cross.snapshot(), results())).toEqual(['Harvest (Cabin server)'])
    cabin.asked[1].resolve(results([movie(2, 'Harbor Nights')]))
    den.asked[1].resolve(results())
    await flush()
    expect(titles(cross.snapshot(), results())).toEqual(['Harbor Nights (Cabin server)'])

    // The same words again ask nobody; after a keystroke that cancelled calls, they do.
    cross.search('harb')
    expect(cabin.asked).toHaveLength(2)
    cross.search('harbo')
    cross.cancel()
    cross.search('harbo')
    expect(cabin.asked.map((a) => a.q)).toEqual(['har', 'harb', 'harbo', 'harbo'])
    // No words: nothing asked, nothing kept.
    cross.search('')
    expect(cross.snapshot()).toEqual([])
    expect(cabin.asked[3].signal.aborted).toBe(true)
    cross.stop()
  })

  it('leaves out for the visit a server whose failure asking again won’t change', async () => {
    const cabin = server('cabin', 'Cabin server'), den = server('den', 'Den server')
    const signedOut = new Error('401')
    const cross = new CrossSearch([cabin, den], (error) => error === signedOut ? 'signedOut' : undefined)
    cross.search('harbor')
    cabin.asked[0].reject(signedOut)
    den.asked[0].reject(new TypeError('Failed to fetch'))
    await flush()
    expect(mergeResults(results(), true, cross.snapshot()).failed).toEqual([{ id: 'cabin', name: 'Cabin server', reason: 'signedOut' }, { id: 'den', name: 'Den server' }])
    // Try again does nothing for it, and neither does waiting.
    cross.retry('cabin')
    await vi.advanceTimersByTimeAsync(ASK_AGAIN_MS)
    cross.search('harbour')
    expect(cabin.asked).toHaveLength(1)
    expect(den.asked).toHaveLength(2)
    expect(cross.snapshot()[0]).toMatchObject({ state: 'failed', reason: 'signedOut' })
    cross.stop()
  })

  it('lines up the servers whose titles aren’t listed: the current one, the others, then those never asked', () => {
    const part = (id: string, name: string, state: Part['state'], reason?: Part['reason']): Part => ({ id, name, state, slow: false, ...(reason ? { reason } : {}) })
    const parts = [part('cabin', 'Cabin server', 'done'), part('den', 'Den server', 'failed'), part('loft', 'Loft server', 'failed', 'tooOld'), part('shed', 'Shed server', 'asking')]
    const leftOut = [{ id: 'attic', name: 'Attic server', reason: 'signedOut' as const }]
    expect(searchLines({ current: { name: 'Living room server', failed: false, again: false }, parts, again: [], leftOut })).toEqual([
      { id: 'den', name: 'Den server', kind: 'failed' },
      { id: 'loft', name: 'Loft server', kind: 'tooOld' },
      { id: 'attic', name: 'Attic server', kind: 'signedOut' },
    ])
    // The current server gets a line like any other when it fails and another answers; Try again shows as asking.
    expect(searchLines({ current: { name: 'Living room server', failed: true, again: false }, parts, again: ['shed'], leftOut: [] })).toEqual([
      { id: CURRENT, name: 'Living room server', kind: 'failed' },
      { id: 'den', name: 'Den server', kind: 'failed' },
      { id: 'loft', name: 'Loft server', kind: 'tooOld' },
      { id: 'shed', name: 'Shed server', kind: 'asking' },
    ])
    expect(searchLines({ current: { name: 'Living room server', failed: true, again: true }, parts, again: [], leftOut: [] })[0]).toEqual({ id: CURRENT, name: 'Living room server', kind: 'asking' })
    // When none answers there are no lines: it is the search that didn't work.
    const down = [part('den', 'Den server', 'failed'), part('loft', 'Loft server', 'failed', 'tooOld')]
    expect(noneAnswered(true, down)).toBe(true)
    expect(searchLines({ current: { name: 'Living room server', failed: true, again: false }, parts: down, again: [], leftOut })).toEqual([])
    expect(noneAnswered(true, parts)).toBe(false)
    expect(noneAnswered(false, down)).toBe(false)
  })

  it('lists the others when the current server’s search fails, and counts who answered', () => {
    const parts: Part[] = [{ id: 'cabin', name: 'Cabin server', state: 'done', slow: false, results: results([movie(3, 'Harbour Run')]) }]
    expect(titles(parts, undefined, true)).toEqual(['Harbour Run (Cabin server)'])
    expect(mergeResults(undefined, true, parts)).toMatchObject({ settled: true, answered: 1 })
    expect(mergeResults(results(), true, [{ id: 'cabin', name: 'Cabin server', state: 'done', slow: false, results: results() }])).toMatchObject({ answered: 2, settled: true })
  })

  it('words the empty states, the wait and the failure for several servers', () => {
    expect(emptyWords('Lakeside', 3, true)).toEqual({ title: 'Search', body: 'Movies, shows and episodes on your 3 servers, and what is on live TV.' })
    expect(emptyWords('Lakeside', 1, false).body).toBe('Movies, shows and episodes on Lakeside.')
    expect(noMatchWords('harbr', 3, false).title).toBe('Nothing on your 3 servers matches “harbr”')
    expect(noMatchWords('harbr', 1, false).title).toBe('Nothing in your library matches “harbr”')
    expect(stillAsking(['Cabin server'])).toBe('Still asking Cabin server…')
    expect(stillAsking(['Cabin server', 'Den server'])).toBe('Still asking Cabin server and Den server…')
    expect(didNotAnswer('Den server')).toBe('Den server didn’t answer, so its titles aren’t listed.')
    expect(askingAgain('Den server')).toBe('Asking Den server again…')
    expect(leftOutLine('Den server', 'signedOut')).toBe('You’re signed out of Den server here, so its titles aren’t listed.')
    expect(leftOutLine('Den server', 'tooOld')).toBe('Den server is too old for this app, so its titles aren’t listed.')
  })
})
