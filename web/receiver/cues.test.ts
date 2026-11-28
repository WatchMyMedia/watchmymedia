import { describe, expect, it } from 'vitest'
import { cueMarkup, cueSource, loadSize, trackTiming } from './cues'

const track = (over: Record<string, unknown> = {}) => ({ id: 's2', codec: 'subrip', format: 'webvtt' as const, burnInOnly: false, default: false, external: false, forced: false, url: '/api/files/7/subtitles/s2.vtt', ...over })

describe('the receiver’s own subtitles', () => {
  it('reads a text track’s WebVTT, ASS converted, and never draws one burned into the picture', () => {
    expect(cueSource(track(), undefined)).toBe('/api/files/7/subtitles/s2.vtt')
    expect(cueSource(track({ format: 'ass', url: '/api/files/7/subtitles/s2.ass?shift=5000' }), undefined)).toBe('/api/files/7/subtitles/s2.vtt?shift=5000')
    expect(cueSource(track({ format: 'image', url: undefined }), undefined)).toBeUndefined()
    expect(cueSource(track({ burnInOnly: true }), undefined)).toBeUndefined()
    expect(cueSource(track(), 's2')).toBeUndefined()
    expect(cueSource(undefined, undefined)).toBeUndefined()
  })

  it('moves a track by the timing a sender sent until the decision has it', () => {
    expect(trackTiming(track(), new Map())).toEqual({ timingMs: 0, timingShared: false })
    expect(trackTiming(track({ timingMs: 800, timingShared: true }), new Map())).toEqual({ timingMs: 800, timingShared: true })
    expect(trackTiming(track({ timingMs: 800, timingShared: true }), new Map([['s2', -300]]))).toEqual({ timingMs: -300, timingShared: false })
  })

  it('draws cues at the foot of the picture, those placed at the top above, with their text escaped', () => {
    const html = cueMarkup([
      { id: 0, startMs: 0, endMs: 1, lines: ['Who’s <there>?', 'Second line'], top: false },
      { id: 1, startMs: 0, endMs: 1, lines: ['Signs & names'], top: true },
    ], { left: 0, right: 0, top: 50, bottom: 75.6, fontPx: 45.36 })
    expect(html).toContain('top:50.0px')
    expect(html).toContain('bottom:75.6px;font-size:45.4px')
    expect(html).toContain('Who’s &lt;there&gt;?<br>Second line')
    expect(html).toContain('Signs &amp; names')
    expect(cueMarkup([], { left: 0, right: 0, top: 0, bottom: 0, fontPx: 14 })).toBe('')
  })

  it('reads the size a sender passed, Normal when it passed none', () => {
    expect(loadSize(undefined)).toBe('normal')
    expect(loadSize('largest')).toBe('largest')
  })
})
