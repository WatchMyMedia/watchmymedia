import { describe, expect, it } from 'vitest'
import { episodeHeading, fileRequest, playbackMedia, playKey, routeHeading } from './playerSession'

describe('what the player route plays', () => {
  it('asks the engine for files, recordings and trailer files', () => {
    expect(fileRequest({ kind: 'file', fileId: 7, startMs: 5000, title: 'Harbor Lights', subtitle: 'S03 · E07' })).toEqual({ kind: 'file', fileId: 7, startMs: 5000, title: 'Harbor Lights' })
    expect(fileRequest({ kind: 'recording', id: 'r1', title: 'News' })).toEqual({ kind: 'recording', recordingId: 'r1', startMs: undefined, title: 'News' })
    expect(fileRequest({ kind: 'trailer', movieId: 3, fileId: 9, title: 'Trailer' })).toEqual({ kind: 'trailer', fileId: 9, title: 'Trailer' })
    expect(fileRequest({ kind: 'trailer', movieId: 3 })).toBeUndefined()
    expect(fileRequest({ kind: 'live', channelId: 'c' })).toBeUndefined()
  })

  it('heads each play with the route’s words, and an episode it moves on to with the show and the episode', () => {
    expect(routeHeading({ kind: 'file', fileId: 7, title: 'Harbor Lights', subtitle: 'S03 · E07 · The Long Dark' })).toEqual({ title: 'Harbor Lights', line: 'S03 · E07 · The Long Dark' })
    expect(routeHeading({ kind: 'recording', id: 'r' })).toEqual({ title: 'Recording' })
    expect(episodeHeading({ showTitle: 'Harbor Lights', seasonNumber: 3, episodeNumber: 8, title: 'Ebb' })).toEqual({ title: 'Harbor Lights', line: 'Season 3 · Episode 8 · Ebb' })
    expect(playKey({ kind: 'file', fileId: 7 })).toBe('file:7')
    expect(playKey({ kind: 'recording', recordingId: 'r' })).toBe('recording:r')
    expect(playKey(undefined)).toBe('')
  })

  it('names what failed for the TV’s playback errors', () => {
    expect(['file', 'episode', 'trailer', 'recording'].map((k) => playbackMedia(k as never))).toEqual(['movie', 'episode', 'trailer', 'recording'])
  })
})
