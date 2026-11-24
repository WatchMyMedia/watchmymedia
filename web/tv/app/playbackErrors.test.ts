import { beforeEach, describe, expect, it } from 'vitest'
import { clearPlaybackErrors, MAX_PLAYBACK_ERRORS, playbackErrors, recordPlaybackError } from './playbackErrors'

describe('the playback errors kept for About', () => {
  beforeEach(() => clearPlaybackErrors())

  it('keeps the last five, newest first', () => {
    for (let i = 0; i < 7; i++) recordPlaybackError({ media: 'movie', kind: 'network', at: i })
    expect(playbackErrors().map((e) => e.at)).toEqual([6, 5, 4, 3, 2])
    expect(playbackErrors()).toHaveLength(MAX_PLAYBACK_ERRORS)
  })

  it('keeps facts only: a status, a channel number, nothing else', () => {
    recordPlaybackError({ media: 'live', kind: 'network', status: 503, channel: '7.1', at: 1, ...{ title: 'Secret title', url: 'http://x/?access_token=abc' } } as never)
    expect(playbackErrors()[0]).toEqual({ media: 'live', kind: 'network', status: 503, channel: '7.1', at: 1 })
  })

  it('refuses what it doesn’t know, and channel names that aren’t numbers', () => {
    recordPlaybackError({ media: 'podcast', kind: 'network', at: 1 } as never)
    recordPlaybackError({ media: 'live', kind: 'format', channel: 'http://x/a b', at: 2 })
    expect(playbackErrors()).toEqual([{ media: 'live', kind: 'format', at: 2 }])
  })
})
