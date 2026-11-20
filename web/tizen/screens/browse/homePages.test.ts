import { describe, expect, it } from 'vitest'
import { END_EDIT, HERO_PLAY, headingLine, homeDrawable, homeIsEmpty, homePages, homeScreenRows, pageOfFocus, pageStep, peekLine, rowCounter, rowGroup, totalsLine } from './homePages'
import { home, item, row } from './testData'

const continueRow = row({ id: 'continue', style: 'continue', title: 'Continue watching', items: undefined, continue: [{ kind: 'movie', title: 'A', fileId: 1, positionMs: 1, durationMs: 2, updatedAt: '' }] })
const shows = row({ id: 'recentlyAddedShows', title: 'Recently added · TV Shows', items: [item({ kind: 'show' })] })
const live = row({ id: 'live', style: 'live', title: 'Live TV', items: undefined })
const list = row({ id: 'list:3', title: 'Best of the 1980s' })

describe('Home’s screens', () => {
  it('draws Live TV’s row only when there are channels, and what the web draws besides', () => {
    expect(homeDrawable(live, false)).toBe(false)
    expect(homeDrawable(live, true)).toBe(true)
    expect(homeDrawable(row({ items: [] }), true)).toBe(false)
    expect(homeDrawable(row({ style: 'carousel' }), true)).toBe(false)
    expect(homeScreenRows(home({ rows: [continueRow, live, shows, row({ id: 'x', items: [] })] }), false).map((r) => r.id)).toEqual(['continue', 'recentlyAddedShows'])
  })

  it('is empty without a hero and a row to draw', () => {
    expect(homeIsEmpty(home(), [])).toBe(true)
    expect(homeIsEmpty(home(), [live])).toBe(false)
    expect(homeIsEmpty(home({ hero: { kind: 'movie', title: 'A', kicker: '', facts: '', overview: '' } }), [])).toBe(false)
  })

  it('puts the hero over the first row, a row a screen after it, and Edit rows last', () => {
    expect(homePages(true, [continueRow, shows, list]).map((p) => p.key)).toEqual(['start', 'row-recentlyAddedShows', 'row-list:3', 'end'])
    expect(homePages(true, [continueRow])[0]).toMatchObject({ kind: 'start', row: continueRow })
    expect(homePages(true, []).map((p) => p.kind)).toEqual(['start', 'end'])
    // Without a hero every row is a row screen.
    expect(homePages(false, [continueRow, shows]).map((p) => p.key)).toEqual(['row-continue', 'row-recentlyAddedShows', 'end'])
  })

  it('finds the page a focus id is on', () => {
    const pages = homePages(true, [continueRow, shows, list, row({ id: 'list:33', title: 'Other' })])
    expect(pageOfFocus(pages, HERO_PLAY)).toBe('start')
    expect(pageOfFocus(pages, `${rowGroup(continueRow)}/cw1`)).toBe('start')
    expect(pageOfFocus(pages, 'row/list:3/movie1')).toBe('row-list:3')
    expect(pageOfFocus(pages, 'row/list:33/movie1')).toBe('row-list:33')
    expect(pageOfFocus(pages, END_EDIT)).toBe('end')
    expect(pageOfFocus(pages, 'row/gone/movie1')).toBeUndefined()
    expect(pageOfFocus(pages, undefined)).toBeUndefined()
  })

  it('names the next row along the bottom, and the one before on the last page', () => {
    const pages = homePages(true, [continueRow, shows, live])
    expect(peekLine(pages, 0, true)).toEqual({ text: 'Recently added · TV Shows', up: false })
    expect(peekLine(pages, 1, true)).toEqual({ text: 'Live TV · On now', up: false })
    expect(peekLine(pages, 2, false)).toEqual({ text: 'End of Home', up: false })
    expect(peekLine(pages, 3, false)).toEqual({ text: 'Live TV', up: true })
    expect(headingLine(list, false)).toBe('Best of the 1980s')
  })

  it('counts rows and pages and words the totals', () => {
    expect(rowCounter([continueRow, shows], shows)).toBe('Row 2 of 2')
    expect(pageStep(3, 0, 'up')).toBeUndefined()
    expect(pageStep(3, 0, 'down')).toBe(1)
    expect(pageStep(3, 2, 'down')).toBeUndefined()
    const line = totalsLine({ totals: { movies: 1, shows: 2, episodes: 3418, files: 0, totalSize: 2 * 1024 ** 4 }, lastScannedAt: new Date(Date.now() - 12 * 60_000).toISOString() })
    expect(line).toBe('1 movie · 2 shows · 3,418 episodes · 2.0 TB · last scan 12 min ago')
  })
})
