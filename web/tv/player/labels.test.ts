import { describe, expect, it } from 'vitest'
import type { Decision } from './api'
import { audioWords, automaticStepNote, channelsWords, episodeWords, onlineWords, streamPill, subtitleWords } from './labels'

const decision = (patch: Partial<Decision> = {}): Decision => ({
  protocol: 'file', mode: 'direct', url: '/api/files/1/stream', playId: 'p', durationMs: 1, startMs: 0, resumeMs: 0, reasons: [],
  audio: { copy: true, codec: 'eac3', index: 1, channels: 6 }, video: { copy: true, codec: 'hevc', toneMap: false, width: 3840, height: 2160, hdr: 'hdr10' },
  audioTracks: [{ index: 1, codec: 'eac3', channels: 6, default: true, language: 'en' }], segments: [], subtitles: [], creditsMs: 0, upNextMs: 0, watchedAtMs: 0, ...patch,
})

describe('player words', () => {
  it('says how the file comes, its picture and untouched audio', () => {
    expect(streamPill(decision(), 'original')).toBe('Direct play · 4K HDR · EAC3 5.1')
    expect(streamPill(decision({ mode: 'transcode', video: { copy: false, toneMap: true, width: 1920, height: 1080 }, audio: { copy: false, index: 1, codec: 'aac', channels: 2 } }), '1080')).toBe('Transcode · 1080p · HDR to SDR')
    expect(streamPill(decision({ mode: 'audio', video: { copy: true, toneMap: false } }), '720')).toBe('Audio transcode · 720p')
  })

  it('leads with Auto, and says only the step once Automatic went down', () => {
    expect(streamPill(decision({ auto: { step: 'original', reason: '' } }), 'auto')).toBe('Auto · Direct play · 4K HDR · EAC3 5.1')
    expect(streamPill(decision({ auto: { step: '720', reason: '' } }), 'auto')).toBe('Auto · 720p')
  })

  it('words tracks and results', () => {
    expect(audioWords({ index: 2, codec: 'ac3', channels: 6, default: false, language: 'fr', title: 'Commentary' })).toEqual({ label: 'French', detail: 'AC3 5.1 · Commentary' })
    expect(audioWords({ index: 3, codec: 'aac', channels: 1, default: false })).toEqual({ label: 'Track 3', detail: 'AAC' })
    // ffprobe tags an untagged track und, which Intl.DisplayNames names "root": the TV showed "Audio root".
    expect(audioWords({ index: 1, codec: 'aac', channels: 2, default: true, language: 'und' })).toEqual({ label: 'Track 1', detail: 'AAC Stereo' })
    expect(channelsWords(8)).toBe('7.1')
    expect(subtitleWords({ id: 's1', codec: 'pgs', format: 'image', burnInOnly: true, default: false, external: false, forced: true, language: 'en' })).toEqual({ label: 'English', detail: 'Forced · burned in, restarts playback' })
    expect(subtitleWords({ id: 's2', codec: 'subrip', format: 'webvtt', burnInOnly: false, default: false, external: false, forced: false, language: 'und', title: 'Signs' })).toEqual({ label: 'Signs', detail: undefined })
    expect(subtitleWords({ id: 'd1', codec: 'subrip', format: 'webvtt', burnInOnly: false, default: false, external: true, forced: false, source: 'opensubtitles', language: 'es', release: 'Release.1080p' })).toEqual({ label: 'Spanish', detail: 'OpenSubtitles · Release.1080p' })
    expect(onlineWords({ id: 'r', language: 'de', release: 'R', hashMatch: true, hearingImpaired: true, forced: false, aiTranslated: false, downloads: 1, trusted: true })).toEqual({ label: 'German', detail: 'matches this file · HI · R' })
    expect(episodeWords({ seasonNumber: 3, episodeNumber: 8, title: 'Squall Line' })).toBe('S03 E08 · Squall Line')
  })

  it('notes Automatic’s moves', () => {
    expect(automaticStepNote('original', '720')).toBe('Lowered to 720p for your connection.')
    expect(automaticStepNote('480', '1080')).toBe('Raised to 1080p for your connection.')
    expect(automaticStepNote('720', 'original')).toBe('Connection recovered; playing the original file.')
    expect(automaticStepNote(undefined, '360')).toBe('Lowered to 360p for your connection.')
  })
})
