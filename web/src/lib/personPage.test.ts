import { describe, expect, it } from 'vitest'
import { crewLine, onlyTitle, personCountLine, roleWords } from './personPage'

describe('personCountLine', () => {
  it('counts both kinds when the person has both', () => {
    expect(personCountLine(3, 2)).toBe('5 titles in your library: 3 movies and 2 shows')
    expect(personCountLine(1, 1)).toBe('2 titles in your library: 1 movie and 1 show')
  })
  it('says only the total when one kind is missing', () => {
    expect(personCountLine(1, 0)).toBe('1 title in your library')
    expect(personCountLine(0, 4)).toBe('4 titles in your library')
  })
})

describe('roleWords', () => {
  it('words the part', () => {
    expect(roleWords('Tess Arden')).toBe('as Tess Arden')
    expect(roleWords(' Maren / Old Tam ')).toBe('as Maren / Old Tam')
  })
  it('says nothing without one', () => {
    expect(roleWords(undefined)).toBe('')
    expect(roleWords('  ')).toBe('')
  })
})

describe('onlyTitle', () => {
  const t = (id: number) => ({ id }) as never
  it('finds the one title', () => {
    expect(onlyTitle({ movies: [t(1)], shows: [] })).toEqual({ id: 1 })
    expect(onlyTitle({ movies: [], shows: [t(2)] })).toEqual({ id: 2 })
  })
  it('is nothing for none or several', () => {
    expect(onlyTitle({ movies: [], shows: [] })).toBeUndefined()
    expect(onlyTitle({ movies: [t(1)], shows: [t(2)] })).toBeUndefined()
  })
})

describe('crewLine', () => {
  const c = (name: string, job: string, personId = name.length) => ({ name, job, personId })
  it('says who directed and wrote a movie', () => {
    expect(crewLine([c('Ines Calloway', 'Director'), c('Oren Mallory', 'Screenplay'), c('Sam Rook', 'Producer'), c('Ada Vey', 'Original Music Composer')])).toBe(
      'Directed by Ines Calloway · Written by Oren Mallory',
    )
  })
  it('joins writers once each, however TMDB words the job', () => {
    expect(crewLine([c('A', 'Writer'), c('B', 'Screenplay'), c('A', 'Screenplay')])).toBe('Written by A and B')
  })
  it('falls back to the story when nobody wrote it', () => {
    expect(crewLine([c('Lee', 'Director'), c('Kim', 'Story')])).toBe('Directed by Lee · Story by Kim')
    expect(crewLine([c('Kim', 'Story'), c('Ray', 'Writer')])).toBe('Written by Ray')
  })
  it('says who created a show', () => {
    expect(crewLine([c('Nadia Quill', 'Creator'), c('Emil Stroud', 'Creator'), c('Kenji Arbor', 'Creator')])).toBe('Created by Nadia Quill, Emil Stroud and Kenji Arbor')
  })
  it('counts names past three', () => {
    expect(crewLine([c('A', 'Director'), c('B', 'Director'), c('C', 'Director'), c('D', 'Director'), c('E', 'Director')])).toBe('Directed by A, B, C and 2 more')
  })
  it('is empty with no crew it words', () => {
    expect(crewLine(undefined)).toBe('')
    expect(crewLine([c('P', 'Producer')])).toBe('')
  })
})
