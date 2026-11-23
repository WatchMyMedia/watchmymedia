import { describe, expect, it } from 'vitest'
import { sectionOf } from './sections'

describe('sections', () => {
  it('names the section a path is in', () => {
    expect(sectionOf('/')).toBe('home')
    expect(sectionOf('/movies/4')).toBe('movies')
    expect(sectionOf('/shows/2/seasons/1')).toBe('tv')
    expect(sectionOf('/tv')).toBe('tv')
    expect(sectionOf('/live-tv/recordings')).toBe('live')
    expect(sectionOf('/activity/history')).toBe('activity')
    expect(sectionOf('/settings')).toBeUndefined()
    expect(sectionOf('/search')).toBeUndefined()
  })
})
