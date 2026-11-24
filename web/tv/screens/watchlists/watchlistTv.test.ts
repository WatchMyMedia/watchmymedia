import { describe, expect, it } from 'vitest'
import { buttonHint, cardMenu, changeFailed, nameFailed, tileWords, watchlistsLine, watchlistsOn, type CardMenuOf } from './watchlistTv'

const one = [{ name: 'Watchlist' }]
const three = [{ name: 'Watchlist' }, { name: 'Halloween' }, { name: 'With the kids' }]
const named = [{ id: 501, name: 'Watchlist' }, { id: 502, name: 'Halloween' }]

describe('watchlists on the TV', () => {
  it.each([[undefined, false], [32, false], [33, true], [40, true]])('at API level %s are offered: %s', (level, on) => {
    expect(watchlistsOn(level)).toBe(on)
  })

  it.each([
    [false, false, 'saves it to your Watchlist'],
    [false, true, 'removes it from your Watchlist'],
    [true, true, 'opens Save to watchlist'],
  ])('says what OK on the button does (picker %s, saved %s)', (picker, saved, words) => {
    expect(buttonHint(picker, saved, 'Watchlist')).toBe(words)
  })
})

describe('a card’s actions', () => {
  it.each<[string, CardMenuOf, string, string[]]>([
    ['a movie, with one watchlist', { kind: 'movie', year: 2026, open: 'Open the movie', watched: false, lists: one, saved: false }, 'Movie · 2026', ['Open the movie', 'Mark watched', 'Add to Watchlist']],
    ['a watched movie on it', { kind: 'movie', open: 'Open the movie', watched: true, lists: one, saved: true }, 'Movie', ['Open the movie', 'Mark unwatched', 'Remove from Watchlist']],
    ['a show, with several', { kind: 'show', year: 2022, open: 'Open the show', lists: three, saved: true }, 'TV show · 2022', ['Open the show', 'Save to watchlist…']],
    ['a missing title on Halloween’s page', { kind: 'movie', year: 2026, request: true, lists: three, saved: true, page: { name: 'Halloween' } }, 'Movie · 2026 · Not in your library', ['Request', 'Remove from Halloween']],
    ['a library title on a watchlist’s page', { kind: 'movie', open: 'Open the movie', watched: false, request: true, lists: three, saved: true, page: { name: 'Watchlist' } }, 'Movie', ['Open the movie', 'Mark watched', 'Remove from Watchlist']],
  ])('%s', (_, of, kicker, labels) => {
    const menu = cardMenu(of)
    expect(menu.kicker).toBe(kicker)
    expect(menu.items.map((i) => i.label)).toEqual(labels)
    expect(menu.items[menu.items.length - 1].kind).toBe('watchlist')
  })
})

describe('the Watchlists screen', () => {
  it('words a tile, and says when it keeps watched titles', () => {
    expect(tileWords({ name: 'Halloween', titles: 6, inLibrary: 6, removeWatched: false })).toEqual({
      count: '6 titles · 6 in your library', keeps: 'Keeps watched titles', label: 'Halloween, 6 titles, 6 in your library, keeps watched titles',
    })
    expect(tileWords({ name: 'Rainy days', titles: 0, inLibrary: 0, removeWatched: true })).toEqual({ count: 'No titles yet', label: 'Rainy days, No titles yet' })
  })

  it.each([[0, undefined], [1, '1 watchlist'], [3, '3 watchlists']])('counts %i watchlists', (n, words) => {
    expect(watchlistsLine(n)).toBe(words)
  })
})

describe('refusals, in our words', () => {
  it.each([
    [{ status: 422 }, false, 'A watchlist holds up to 500 titles.'],
    [{ status: 503 }, false, 'Couldn’t save to Halloween. The server is busy.'],
    [new TypeError('fetch failed'), true, 'Couldn’t remove from Halloween. Can’t reach the server.'],
  ])('a change refused with %o', (error, remove, words) => {
    expect(changeFailed(error, { name: 'Halloween', remove })).toBe(words)
  })

  it.each([
    ['a name in use', 'halloween', named, 25, 'You already have a watchlist called Halloween.'],
    ['the limit', 'Rainy days', named, 2, 'You have 2 watchlists, the most you can make.'],
    ['a reason this TV doesn’t know', 'Rainy days', named, 25, 'That name can’t be used. Try another.'],
  ])('a name refused for %s', (_, name, lists, max, words) => {
    expect(nameFailed({ status: 422, message: 'the server’s own words' }, name, lists, max)).toBe(words)
  })

  it('never shows the server’s text', () => {
    expect(nameFailed({ status: 500, message: 'database is locked at /data' }, 'Rainy days', named)).toBe('Couldn’t save the name. The server had a problem.')
  })
})
