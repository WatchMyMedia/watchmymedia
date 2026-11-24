import { describe, expect, it } from 'vitest'
import { airedDay, cardPanel, panelHint } from './cardPanel'
import { channelCard, continueCard, holdHint, holdWords, itemActions, posterLine, rowCards, rowHeading, rowVariant, seeAllRoute, spacedCode, timeLeft, type RowContext } from './rowCards'
import { channel, episode, item, libraries, resume, row } from './testData'

const NOW = Date.UTC(2026, 10, 19, 20, 30)
const ctx = (over: Partial<RowContext> = {}): RowContext => ({ libraries, channels: [], listings: false, now: NOW, ...over })
const home = (target: Parameters<typeof seeAllRoute>[0], r: Parameters<typeof seeAllRoute>[1]) => {
  const route = seeAllRoute(target, r, libraries)
  return route && { kind: 'open' as const, route }
}

describe('a row’s cards', () => {
  it('opens a movie and marks it watched on Hold OK', () => {
    const [card] = rowCards(row({ items: [item({ id: 4, title: 'Glass Orchard', isNew: true })] }), ctx())
    expect(card).toMatchObject({ key: 'movie4', variant: 'poster', title: 'Glass Orchard', subtitle: 'added yesterday', badges: [{ label: 'New' }] })
    expect(card.press).toEqual({ kind: 'open', route: { name: 'movie', id: 4 } })
    expect(card.hold).toEqual({ kind: 'watched', movieId: 4, watched: true })
    const [seen] = rowCards(row({ items: [item({ watched: true })] }), ctx())
    expect(seen.hold).toEqual({ kind: 'watched', movieId: 1, watched: false })
    expect(seen.label).toContain('watched')
  })

  it('opens a Recently added show at what arrived: the episode, the season, or the show', () => {
    const shows = row({ id: 'recentlyAddedShows', items: [
      item({ kind: 'show', id: 7, subtitle: 'S03E07', episodeId: 70, seasonNumber: 3 }),
      item({ kind: 'show', id: 8, subtitle: 'Season 1' }),
      item({ kind: 'show', id: 9, subtitle: 'Complete series' }),
    ] })
    const cards = rowCards(shows, ctx())
    expect(cards.map((c) => c.press)).toEqual([
      { kind: 'open', route: { name: 'show', id: 7, episodeId: 70 } },
      { kind: 'open', route: { name: 'show', id: 8, season: 1 } },
      { kind: 'open', route: { name: 'show', id: 9 } },
    ])
    expect(cards[0].subtitle).toBe('S03 E07 · added yesterday')
    expect(cards[0].hold).toBeUndefined()
  })

  it('says an unmatched file isn’t matched rather than opening it', () => {
    expect(rowCards(row({ items: [item({ kind: 'unmatched' })] }), ctx())[0].press).toEqual({ kind: 'notMatched' })
  })

  it('resumes Continue watching at once, and opens its page on Hold OK', () => {
    const [card] = rowCards(row({ id: 'continue', style: 'continue', items: undefined, continue: [resume()] }), ctx())
    expect(card.press).toEqual({ kind: 'play', request: { kind: 'file', fileId: 9, startMs: 40 * 60_000, title: 'Saltmarsh', subtitle: 'Season 1 · Episode 9 · Low Water', backdropPath: undefined } })
    expect(card.hold).toEqual({ kind: 'open', route: { name: 'show', id: 5 } })
    expect(card.progress).toBeCloseTo(40 / 48)
  })

  it('opens an episode on its show and plays it on Hold OK', () => {
    const [card] = rowCards(row({ id: 'recentlyAired', style: 'episode', items: undefined, episodes: [episode({ isNew: true })] }), ctx())
    expect(card).toMatchObject({ variant: 'episode', title: 'Harbor Lights', subtitle: 'S03 E07 · The Long Dark' })
    expect(card.press).toEqual({ kind: 'open', route: { name: 'show', id: 7, episodeId: 70 } })
    expect(card.hold).toMatchObject({ kind: 'play', request: { kind: 'file', fileId: 700 } })
    const [missing] = rowCards(row({ style: 'episode', episodes: [episode({ hasFile: false, fileId: undefined })] }), ctx())
    expect(missing.hold).toEqual(missing.press)
    expect(missing.badges).toEqual([{ label: 'Not in library' }])
  })

  it('ends a row with See all: its library sorted its way, or a list', () => {
    const added = row({ seeAll: { kind: 'library', libraryId: 3, sort: 'added', genre: 'Drama' } })
    const cards = rowCards(added, ctx({ seeAll: home }))
    expect(cards).toHaveLength(2)
    expect(cards[1]).toMatchObject({ key: 'all', variant: 'poster', more: { title: 'See all movies', detail: 'By date added', glyph: 'grid' } })
    expect(cards[1].press).toEqual({ kind: 'open', route: { name: 'seeAll', libraryId: 3, title: 'Recently added', sort: 'added', filter: undefined, genre: 'Drama', decade: undefined } })
    // The first library of a type is named too.
    expect(seeAllRoute({ kind: 'library', type: 'tv', search: { sort: 'aired' } }, row(), libraries)).toMatchObject({ name: 'seeAll', libraryId: 2 })
    // From a Movies or TV screen a list's page opens on that type, as a watchlist's row there holds only it.
    expect(seeAllRoute({ kind: 'list', listId: 501, type: 'movie' }, row(), libraries)).toEqual({ name: 'list', id: 501, type: 'movie' })
    const list = rowCards(row({ id: 'list:3', listId: 3, style: 'wide', seeAll: { kind: 'list', listId: 3 } }), ctx({ seeAll: home }))
    expect(list[1]).toMatchObject({ variant: 'wide', more: { title: 'See all', glyph: 'list' }, press: { kind: 'open', route: { name: 'list', id: 3 } } })
    // Nowhere to go here: no card.
    expect(rowCards(added, ctx())).toHaveLength(1)
    expect(rowCards(added, ctx({ seeAll: () => undefined }))).toHaveLength(1)
  })

  it('draws Live TV’s channels with what is on, and the guide at the end', () => {
    const on = channel({ now: { id: 'p', title: 'Night Market', start: new Date(NOW - 30 * 60_000).toISOString(), end: new Date(NOW + 30 * 60_000).toISOString(), categories: ['News'] } })
    const cards = rowCards(row({ id: 'live', style: 'live', items: undefined }), ctx({ channels: [on, channel({ id: 'c9', number: '9' })], listings: true }))
    expect(cards.map((c) => c.key)).toEqual(['chc7', 'chc9', 'guide'])
    expect(cards[0]).toMatchObject({ title: 'Night Market', live: { number: '7.1', name: 'KLAK', now: 'Night Market' }, press: { kind: 'play', request: { kind: 'live', channelId: 'c7' } } })
    expect(cards[0].progress).toBeCloseTo(0.5)
    expect(cards[0].subtitle).toContain('30 min left')
    expect(cards[1].subtitle).toBe('No listings')
    expect(cards[2].press).toEqual({ kind: 'open', route: { name: 'liveGuide' } })
    // Without listings: Watch a channel, no guide card.
    expect(rowCards(row({ style: 'live' }), ctx({ channels: [on] })).map((c) => c.key)).toEqual(['chc7'])
    expect(channelCard(on, false, NOW).live?.now).toBe('Watch live')
  })

  it('words lines, headings and Hold OK', () => {
    expect(spacedCode('S03E07')).toBe('S03 E07')
    expect(spacedCode('Season 1')).toBe('Season 1')
    expect(spacedCode('')).toBeUndefined()
    expect(timeLeft(72 * 60_000)).toBe('1 h 12 min left')
    expect(posterLine(row({ style: 'wide', id: 'recentlyReleased' }), item({ releaseDate: '2026-10-17' }))).toMatch(/2026/)
    expect(posterLine(row({ id: 'list:1' }), item({ year: 1986 }))).toBe('1986')
    expect(rowVariant({ style: 'continue' })).toBe('continue')
    expect(rowVariant({ style: 'unknown' })).toBe('poster')
    expect(rowHeading(row(), false)).toEqual(['Recently added', 'Movies'])
    expect(rowHeading(row({ id: 'list:3', listId: 3, title: 'Best · of', subtitle: '18 of 50', source: { kind: 'tmdb', name: 'TMDB' } }), false)).toEqual(['Best · of', '18 of 50 · From TMDB'])
    expect(rowHeading(row({ style: 'live' }), true, '8:30 PM')).toEqual(['On now', 'Live TV · 8:30 PM'])
    expect(rowHeading(row({ style: 'live' }), false)).toEqual(['Live TV', 'Watch a channel'])
    expect(holdWords({ kind: 'watched', movieId: 1, watched: true })).toBe('marks it watched')
    expect(holdWords(undefined)).toBeUndefined()
  })
})

describe('a title’s actions', () => {
  // Hold OK opens them where the server keeps watchlists, on a title TMDB has matched; elsewhere it does what it did.
  it.each<[string, ReturnType<typeof item>, boolean, object]>([
    ['a movie', item({ id: 4, tmdbId: 77 }), true, { kind: 'menu' }],
    ['a show', item({ kind: 'show', id: 7, tmdbId: 78 }), true, { kind: 'menu' }],
    ['a movie TMDB hasn’t matched', item({ id: 4 }), true, { kind: 'watched', movieId: 4, watched: true }],
    ['a movie on an older server', item({ id: 4, tmdbId: 77 }), false, { kind: 'watched', movieId: 4, watched: true }],
  ])('Hold OK on %s', (_, title, menu, hold) => {
    expect(itemActions(title, menu).hold).toMatchObject(hold)
  })

  it('says a watchlist’s row by its name and the title’s type, and that Hold OK opens the menu', () => {
    const halloween = row({ id: 'list:502', listId: 502, title: 'Halloween', items: [item({ kind: 'show', id: 7, tmdbId: 78 })] })
    const cards = rowCards(halloween, ctx({ menu: true }))
    expect(cardPanel(halloween, cards, 0, ctx())).toMatchObject({ kicker: 'Halloween · TV show' })
    expect(holdWords(cards[0].hold)).toBe('for its menu')
  })
})

describe('the panel over a row screen', () => {
  it('tells the focused card’s kicker, facts and rating, and no quality', () => {
    const r = row({ id: 'recentlyAddedShows', title: 'Recently added · TV Shows', items: [item({ kind: 'show', title: 'Harbor Lights', subtitle: 'S03E07', episodeId: 70, seasonNumber: 3, year: 2022, episodeCount: 27, genres: ['Drama', 'Mystery', 'Crime'], quality: '4K', hdr: true, rating: 7.84, backdropPath: '/b.jpg' })] })
    const cards = rowCards(r, ctx())
    expect(cardPanel(r, cards, 0, ctx())).toEqual({
      kicker: 'Recently added · S03 E07 · added yesterday', title: 'Harbor Lights', facts: ['2022', '27 episodes', 'Drama, Mystery'], rating: '★ 7.8',
      overview: '', target: 'episode', picture: '/b.jpg', seed: 'Harbor Lights',
    })
    expect(cardPanel(r, cards, 5, ctx())).toBeUndefined()
  })

  it('ranks a list’s titles and words the other styles', () => {
    const list = row({ id: 'list:3', listId: 3, source: { kind: 'tmdb', name: 'TMDB' }, items: [item(), item({ id: 2, runtimeMin: 118, year: 2026 })] })
    expect(cardPanel(list, rowCards(list, ctx()), 1, ctx())).toMatchObject({ kicker: '#2 on the list', facts: ['2026', '1 h 58 min'], target: 'movie' })
    const y = new Date(Date.now() - 86_400_000)
    const yesterday = `${y.getFullYear()}-${String(y.getMonth() + 1).padStart(2, '0')}-${String(y.getDate()).padStart(2, '0')}`
    const aired = row({ id: 'recentlyAired', style: 'episode', title: 'Recently aired · Episodes', episodes: [episode({ isNew: true, airDate: yesterday })] })
    expect(cardPanel(aired, rowCards(aired, ctx()), 0, ctx())).toMatchObject({ kicker: 'New episode · aired yesterday', title: 'The Long Dark', facts: ['Harbor Lights', 'S03 E07'] })
    const cw = row({ id: 'continue', style: 'continue', continue: [resume()] })
    expect(cardPanel(cw, rowCards(cw, ctx()), 0, ctx())).toMatchObject({ kicker: 'Continue watching · 8 min left', target: 'resume', facts: ['Season 1 · Episode 9 · Low Water', '48 min'] })
  })

  it('says when an episode aired by the calendar, never by the hour', () => {
    const now = new Date(2026, 10, 19, 1, 30).getTime()
    expect(airedDay('2026-11-19', now)).toBe('today')
    expect(airedDay('2026-11-18', now)).toBe('yesterday')
    expect(airedDay('2026-11-15', now)).toBe('4 days ago')
    expect(airedDay('2026-10-01', now)).toMatch(/2026/)
  })

  it('tells See all and the guide in their own words', () => {
    const r = row({ seeAll: { kind: 'library', libraryId: 1, sort: 'added' } })
    const cards = rowCards(r, ctx({ seeAll: home }))
    expect(cardPanel(r, cards, 1, ctx())).toMatchObject({ kicker: 'Recently added', title: 'See all movies', facts: ['By date added'], target: 'library' })
    const live = row({ style: 'live' })
    const liveCards = rowCards(live, ctx({ channels: [channel()], listings: true }))
    expect(cardPanel(live, liveCards, 1, ctx({ listings: true }))).toMatchObject({ kicker: 'Live TV', title: 'Full guide', target: 'guide' })
    expect(cardPanel(live, liveCards, 0, ctx({ channels: [channel()], listings: true }))).toMatchObject({ kicker: 'Live TV · 7.1 KLAK', facts: ['No listings'], target: 'channel' })
  })

  it('says what OK and Hold OK do, and that ◀ leaves the first card for the menu', () => {
    const card = rowCards(row(), ctx())[0]
    expect(panelHint('movie', card, true)).toBe('opens the movie · hold OK marks it watched · ▶ more in this row · ◀ menu · ▼ next row')
    expect(panelHint('show', undefined, false)).toBe('opens the show · ◀ ▶ more in this row · ▼ next row')
  })
})

describe('a Continue watching card', () => {
  const movie = { kind: 'movie', title: 'Low Water', fileId: 11, movieId: 4, positionMs: 30 * 60_000, durationMs: 90 * 60_000, updatedAt: '2026-11-20T00:00:00Z' } as const

  it('resumes on OK and opens its page on Hold OK below API level 34', () => {
    const card = continueCard(movie)
    expect(card.press).toMatchObject({ kind: 'play', request: { kind: 'file', fileId: 11, startMs: 30 * 60_000 } })
    expect(card.hold).toEqual({ kind: 'open', route: { name: 'movie', id: 4 } })
  })

  it('opens its actions on Hold OK from level 34, Remove from Continue watching among them', () => {
    expect(continueCard(movie, true).hold).toEqual({ kind: 'continueMenu', item: movie })
    expect(holdHint(continueCard(movie, true).hold)).toBe('Its menu')
  })
})
