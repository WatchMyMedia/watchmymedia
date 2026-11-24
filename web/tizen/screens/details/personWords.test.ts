import { describe, expect, it } from 'vitest'
import { castPress, personLine, personTitleLine, personTitleRoute } from './personWords'

describe('a person’s page', () => {
  it('counts their titles on the server', () => {
    expect(personLine(3, 2, 'Living room server')).toBe('3 movies and 2 shows on Living room server')
    expect(personLine(1, 0, 'Cabin')).toBe('1 movie on Cabin')
    expect(personLine(0, 1, 'Cabin')).toBe('1 show on Cabin')
    expect(personLine(0, 0, 'Cabin')).toBe('Nothing of theirs is on Cabin')
  })

  it('says the part they played and the year under each title', () => {
    expect(personTitleLine({ character: 'Nell Kerr', year: 2026 })).toBe('As Nell Kerr · 2026')
    expect(personTitleLine({ year: 2019 })).toBe('2019')
    expect(personTitleLine({})).toBeUndefined()
  })

  it('opens a movie or a show, and nothing for an unmatched file', () => {
    expect(personTitleRoute({ kind: 'movie', id: 7 })).toEqual({ name: 'movie', id: 7 })
    expect(personTitleRoute({ kind: 'show', id: 8 })).toEqual({ name: 'show', id: 8 })
    expect(personTitleRoute({ kind: 'unmatched', id: 9 })).toBeUndefined()
  })
})

describe('OK on a cast card', () => {
  it('opens the person from API level 34, by their TMDB id', () => {
    expect(castPress({ personId: 70_001, name: 'Ida Markham' }, true)).toEqual({ kind: 'open', route: { name: 'person', personId: 70_001, who: 'Ida Markham' } })
  })

  it('says why when the server hasn’t matched the credit to a person yet', () => {
    expect(castPress({ name: 'Wren Adeyemi' }, true)).toEqual({ kind: 'unknown' })
  })

  it('does nothing below level 34, as before', () => {
    expect(castPress({ personId: 70_001, name: 'Ida Markham' }, false)).toBeUndefined()
  })
})
