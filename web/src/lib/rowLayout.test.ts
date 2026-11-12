import { describe, expect, it } from 'vitest'
import {
  addRow, addable, countWords, dragShift, dragStride, dropIndex, editTitle, layoutView, mergeDraft, moveRow, moveRowTo, movedWords, overWords, ownerLabel, placements,
  samePlacement, setHidden, type RowLayoutEntry,
} from './rowLayout'

const row = (id: string, over: Partial<RowLayoutEntry> = {}): RowLayoutEntry => ({ id, title: id, style: 'poster', kind: 'builtin', hidden: false, shown: true, ...over })
const ids = (rows: { id: string }[]) => rows.map((r) => r.id)

describe('moving rows with the buttons', () => {
  const rows = ['a', 'b', 'c'].map((id) => row(id))

  it('swaps with the row above or below', () => {
    expect(ids(moveRow(rows, 1, -1))).toEqual(['b', 'a', 'c'])
    expect(ids(moveRow(rows, 1, 1))).toEqual(['a', 'c', 'b'])
  })

  it('leaves the list as it was at either end and past it', () => {
    expect(ids(moveRow(rows, 0, -1))).toEqual(['a', 'b', 'c'])
    expect(ids(moveRow(rows, 2, 1))).toEqual(['a', 'b', 'c'])
    expect(ids(moveRow(rows, 5, -1))).toEqual(['a', 'b', 'c'])
  })

  it('never changes the list it was given', () => {
    moveRow(rows, 1, -1)
    expect(ids(rows)).toEqual(['a', 'b', 'c'])
  })
})

describe('moving rows by dragging', () => {
  // Rows 40 high with 8 between: tops at 0, 48, 96, 144.
  const spans = [0, 48, 96, 144].map((top) => ({ top, height: 40 }))

  it('moves a row to the place counted without it', () => {
    expect(moveRowTo(['a', 'b', 'c', 'd'], 0, 2)).toEqual(['b', 'c', 'a', 'd'])
    expect(moveRowTo(['a', 'b', 'c', 'd'], 3, 0)).toEqual(['d', 'a', 'b', 'c'])
    expect(moveRowTo(['a', 'b', 'c', 'd'], 1, 1)).toEqual(['a', 'b', 'c', 'd'])
    expect(moveRowTo(['a', 'b'], 0, 9)).toEqual(['b', 'a'])
    expect(moveRowTo(['a', 'b'], 4, 0)).toEqual(['a', 'b'])
  })

  it('lands where the dragged row’s middle passes other rows’ middles', () => {
    // The first row (middle at 20) not moved, or moved a little.
    expect(dropIndex(20, spans, 0)).toBe(0)
    expect(dropIndex(60, spans, 0)).toBe(0)
    // Past the second row's middle (68).
    expect(dropIndex(70, spans, 0)).toBe(1)
    // Below the last row.
    expect(dropIndex(400, spans, 0)).toBe(3)
    // The last row dragged above everything.
    expect(dropIndex(-50, spans, 3)).toBe(0)
    expect(dropIndex(100, spans, 3)).toBe(2)
  })

  it('agrees with moveRowTo: dropping in place changes nothing', () => {
    for (let from = 0; from < spans.length; from++) {
      const centre = spans[from].top + spans[from].height / 2
      expect(moveRowTo([0, 1, 2, 3], from, dropIndex(centre, spans, from))).toEqual([0, 1, 2, 3])
    }
  })

  it('moves rows aside by the dragged row’s height and the gap', () => {
    expect(dragStride(spans, 0)).toBe(48)
    expect(dragStride(spans, 3)).toBe(48)
    expect(dragStride([{ top: 0, height: 40 }], 0)).toBe(40)
    expect(dragStride([], 0)).toBe(0)
  })

  it('shifts only the rows between where it was and where it is', () => {
    // From 0 to 2: rows 1 and 2 go up.
    expect([0, 1, 2, 3].map((i) => dragShift(i, 0, 2, 48))).toEqual([0, -48, -48, 0])
    // From 3 to 1: rows 1 and 2 go down.
    expect([0, 1, 2, 3].map((i) => dragShift(i, 3, 1, 48))).toEqual([0, 48, 48, 0])
    expect([0, 1, 2].map((i) => dragShift(i, 1, 1, 48))).toEqual([0, 0, 0])
  })
})

describe('hiding and adding rows', () => {
  const rows = [row('continue'), row('recentlyAired', { hidden: true }), row('list:4', { kind: 'list', hidden: true, owner: 'everyone' })]

  it('hides and shows a row where it is', () => {
    expect(setHidden(rows, 'continue', true).map((r) => r.hidden)).toEqual([true, true, true])
    expect(ids(setHidden(rows, 'recentlyAired', false))).toEqual(['continue', 'recentlyAired', 'list:4'])
    expect(setHidden(rows, 'recentlyAired', false)[1].hidden).toBe(false)
  })

  it('offers the hidden rows, lists made for other screens among them', () => {
    expect(ids(addable(rows))).toEqual(['recentlyAired', 'list:4'])
  })

  it('puts an added row first, shown', () => {
    const added = addRow(rows, 'list:4')
    expect(ids(added)).toEqual(['list:4', 'continue', 'recentlyAired'])
    expect(added[0].hidden).toBe(false)
    expect(ids(addRow(rows, 'nope'))).toEqual(ids(rows))
  })

  it('saves every row in order, hidden ones too', () => {
    expect(placements(rows)).toEqual([
      { id: 'continue', hidden: false },
      { id: 'recentlyAired', hidden: true },
      { id: 'list:4', hidden: true },
    ])
  })

  it('tells a changed draft from the saved layout', () => {
    expect(samePlacement(rows, rows.map((r) => ({ ...r })))).toBe(true)
    expect(samePlacement(rows, moveRow(rows, 0, 1))).toBe(false)
    expect(samePlacement(rows, setHidden(rows, 'continue', true))).toBe(false)
    expect(samePlacement(rows, rows.slice(1))).toBe(false)
  })
})

describe('merging the latest layout into a draft', () => {
  it('keeps the draft’s order and hidden choices, with the latest titles and notes', () => {
    const draft = [row('b', { hidden: true }), row('a')]
    const latest = [row('a', { title: 'A', note: 'Nothing yet', shown: false }), row('b', { title: 'B' })]
    const merged = mergeDraft(draft, latest)
    expect(ids(merged)).toEqual(['b', 'a'])
    expect(merged[0]).toMatchObject({ title: 'B', hidden: true })
    expect(merged[1]).toMatchObject({ title: 'A', note: 'Nothing yet', shown: false, hidden: false })
  })

  it('drops rows the layout no longer has, such as a deleted list', () => {
    expect(ids(mergeDraft([row('a'), row('list:1'), row('b')], [row('a'), row('b')]))).toEqual(['a', 'b'])
  })

  it('puts a list just made first, where the server shows it', () => {
    expect(ids(mergeDraft([row('b'), row('a')], [row('list:9'), row('a'), row('b')]))).toEqual(['list:9', 'b', 'a'])
  })

  it('puts any other new row after the nearest row before it', () => {
    expect(ids(mergeDraft([row('c'), row('a')], [row('a'), row('new'), row('c')]))).toEqual(['c', 'a', 'new'])
    expect(ids(mergeDraft([row('c'), row('a')], [row('a'), row('c'), row('n1'), row('n2')]))).toEqual(['c', 'n1', 'n2', 'a'])
  })
})

describe('words', () => {
  it('says where a row moved, counting from one', () => {
    expect(movedWords('Continue watching', 1, 6)).toBe('Continue watching, position 2 of 6')
  })

  it('names whose list it is, and skips an owner it doesn’t know', () => {
    expect(ownerLabel('me')).toBe('Your list')
    expect(ownerLabel('everyone')).toBe('Household list')
    expect(ownerLabel('team')).toBe('List')
    expect(ownerLabel(undefined)).toBe('List')
  })
})

describe('what the editor says about each row', () => {
  const saved = [
    row('continue'),
    row('list:15', { kind: 'list', shown: false, note: 'Nothing from this list is in your library yet' }),
    row('recentlyAired', { hidden: true, shown: false }),
    row('recentlyAddedMovies'),
  ]

  it('keeps an empty list’s note, and counts the rows', () => {
    const v = layoutView(saved, saved)
    expect(v.rows.map((r) => r.note)).toEqual([undefined, 'Nothing from this list is in your library yet', undefined, undefined])
    expect(v).toMatchObject({ total: 4, shown: 2, hidden: 1, over: 0 })
    expect(countWords(v)).toBe('4 rows · 2 shown · 1 hidden')
  })

  it('counts a row shown again as one that draws', () => {
    expect(layoutView(setHidden(saved, 'recentlyAired', false), saved)).toMatchObject({ shown: 3, hidden: 0 })
  })

  it('marks the rows past the limit, with a line above the first, as the draft orders them', () => {
    const many = Array.from({ length: 5 }, (_, i) => row(`r${i}`))
    const v = layoutView(many, many, 3)
    expect(v.rows.map((r) => r.over)).toEqual([false, false, false, true, true])
    expect(v.rows.map((r) => r.divider)).toEqual([false, false, false, true, false])
    expect(v.rows[3].note).toBe('Not shown: too many rows')
    expect(v).toMatchObject({ shown: 3, over: 2 })
    // Hiding one above brings one back.
    expect(layoutView(setHidden(many, 'r0', true), many, 3)).toMatchObject({ shown: 3, over: 1 })
    expect(overWords(2)).toBe('The last 2 rows aren’t shown.')
    expect(overWords(1)).toBe('The last row isn’t shown.')
  })

  it('doesn’t take a row the server left out for the limit as empty', () => {
    const many = [...Array.from({ length: 3 }, (_, i) => row(`r${i}`)), row('r3', { shown: false, note: 'Not shown: too many rows' })]
    const v = layoutView(many, many, 3)
    expect(v.rows[3]).toMatchObject({ over: true, divider: true })
    // Moved to the top, it draws and pushes the last one out.
    expect(layoutView(moveRowTo(many, 3, 0), many, 3).rows.map((r) => r.over)).toEqual([false, false, false, true])
  })

  it('names the screen being edited', () => {
    expect(editTitle('Home')).toBe('Edit Home’s rows')
    expect(editTitle('Movies')).toBe('Edit Movies’ rows')
  })
})
