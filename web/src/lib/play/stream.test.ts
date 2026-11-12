import { describe, expect, it } from 'vitest'
import { hlsStartPosition, requestedPlaylist, timelineOffset } from './stream'

describe('file playback timeline', () => {
  it('requests VOD for a completed file and event for a growing recording', () => {
    expect(requestedPlaylist()).toBe('vod')
    expect(requestedPlaylist('recording-1')).toBe('event')
  })

  it('resumes and reloads VOD at the requested file position', () => {
    const vod = { protocol: 'hls', playlist: 'vod', startMs: 95_000 }
    expect(timelineOffset(vod)).toBe(0)
    expect(hlsStartPosition(vod, 95)).toBe(95)
    expect(hlsStartPosition(vod, 0)).toBe(0)
  })

  it('keeps a bounded event fallback on its session-relative timeline', () => {
    const event = { protocol: 'hls', playlist: 'event', startMs: 95_000 }
    expect(timelineOffset(event)).toBe(95)
    expect(hlsStartPosition(event, 95)).toBe(0)
  })
})
