import { describe, expect, it } from 'vitest'
import { hlsStartPosition, requestedPlaylist, resumePlan, timelineOffset } from './stream'

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

  it('starts an event session that began on the keyframe before the asked-for time that far in', () => {
    const event = { protocol: 'hls', playlist: 'event', startMs: 20_000 }
    expect(hlsStartPosition(event, 25)).toBe(5)
    expect(hlsStartPosition(event, 18)).toBe(0)
    expect(hlsStartPosition({ protocol: 'file', startMs: 0 }, 25)).toBe(25)
  })
})

describe('resuming a play that asked for no start', () => {
  it('plays from the start with nothing worth resuming', () => {
    expect(resumePlan({ protocol: 'hls', playlist: 'vod', startMs: 0, resumeMs: 0 })).toBeUndefined()
    expect(resumePlan({ protocol: 'hls', playlist: 'vod', startMs: 0, resumeMs: 5_000 })).toBeUndefined()
  })

  it('seeks a whole timeline to the resume point with one request', () => {
    expect(resumePlan({ protocol: 'hls', playlist: 'vod', startMs: 0, resumeMs: 35_000 })).toEqual({ fromMs: 35_000, again: false })
    expect(resumePlan({ protocol: 'file', startMs: 0, resumeMs: 35_000 })).toEqual({ fromMs: 35_000, again: false })
  })

  it('keeps an event session that already starts at the resume point, on the keyframe before it', () => {
    expect(resumePlan({ protocol: 'hls', playlist: 'event', startMs: 30_000, resumeMs: 35_000 })).toEqual({ fromMs: 35_000, again: false })
    expect(resumePlan({ protocol: 'hls', playlist: 'event', startMs: 35_000, resumeMs: 35_000 })).toEqual({ fromMs: 35_000, again: false })
  })

  it('asks again where an older server started the event session at 0', () => {
    expect(resumePlan({ protocol: 'hls', playlist: 'event', startMs: 0, resumeMs: 35_000 })).toEqual({ fromMs: 35_000, again: true })
    expect(resumePlan({ protocol: 'hls', playlist: 'event', startMs: 40_000, resumeMs: 35_000 })).toEqual({ fromMs: 35_000, again: true })
  })
})
