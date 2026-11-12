import { describe, expect, it } from 'vitest'
import type { components } from '../src/api/schema'
import type { LoadData } from '../src/cast/protocol'
import { manualSubtitleSelection, nextEpisodeSubtitles, startingSubtitle, subtitleRequest } from './subtitles'

const load = (subtitleMode?: LoadData['subtitleMode']): LoadData => ({ v: 1, server: { id: 'fictional', urls: [] }, token: 'c1.fixture', fileId: 42, subtitleMode })
const track = (id: string): components['schemas']['SubtitleTrack'] => ({ id, codec: 'subrip', format: 'webvtt', forced: false, default: false, burnInOnly: false, external: false })
const decision = { subtitleId: 's2', subtitles: [track('s2'), track('s3')] }

describe('receiver subtitle preference adoption', () => {
  it('forwards only an explicit known mode, gated at API level 18', () => {
    for (const mode of ['auto', 'off'] as const) {
      expect(JSON.stringify(subtitleRequest(load(mode), 17))).toBe('{}')
      expect(subtitleRequest(load(mode), 18)).toEqual({ subtitleMode: mode, subtitleId: undefined })
    }
    expect(JSON.stringify(subtitleRequest(load(), 18))).toBe('{}')
    expect(JSON.stringify(subtitleRequest({ ...load(), subtitleMode: 'future' } as unknown as LoadData, 18))).toBe('{}')
  })

  it('retains manual IDs on older servers without adding the newer field', () => {
    expect(JSON.stringify(subtitleRequest({ ...load('auto'), subtitleId: 's3' }, 17))).toBe('{"subtitleId":"s3"}')
  })

  it('selects the server default only for an opted-in sender on a supporting server', () => {
    expect(startingSubtitle(load('auto'), decision, 18)).toBe('s2')
    expect(startingSubtitle(load(), decision, 18)).toBeUndefined()
    expect(startingSubtitle(load('auto'), decision, 17)).toBeUndefined()
    expect(startingSubtitle(load('off'), decision, 18)).toBeUndefined()
    expect(startingSubtitle(load('auto'), { ...decision, subtitleId: 'not-in-the-decision' }, 18)).toBeUndefined()
  })

  it('manual selection wins over a different server default, including with mode off', () => {
    expect(startingSubtitle({ ...load('auto'), subtitleId: 's3' }, decision, 18)).toBe('s3')
    expect(startingSubtitle({ ...load('off'), subtitleId: 's3' }, decision, 18)).toBe('s3')
  })

  it('manual Off survives both quality and audio reloads', () => {
    const off = manualSubtitleSelection(load('auto'), null)
    for (const reload of [{ ...off, quality: '480' }, { ...off, audioIndex: 3 }]) {
      expect(subtitleRequest(reload, 18)).toEqual({ subtitleId: undefined, subtitleMode: 'off' })
      expect(startingSubtitle(reload, decision, 18)).toBeUndefined()
    }
  })

  it('an automatic starting track can follow the changed audio without becoming a manual pick', () => {
    const automatic = { ...load('auto'), quality: '720', audioIndex: 2 }
    expect(subtitleRequest(automatic, 18)).toEqual({ subtitleId: undefined, subtitleMode: 'auto' })
    expect(startingSubtitle(automatic, { ...decision, subtitleId: 's3' }, 18)).toBe('s3')
    const manual = { ...manualSubtitleSelection(automatic, 's2'), audioIndex: 3 }
    expect(startingSubtitle(manual, { ...decision, subtitleId: 's3' }, 18)).toBe('s2')
  })

  it('restores defaults for the next episode only when the original LOAD opted in', () => {
    const off = manualSubtitleSelection(load('auto'), null)
    const next = nextEpisodeSubtitles(off, 'auto')
    expect(next.subtitleId).toBeNull()
    expect(startingSubtitle(next, decision, 18)).toBe('s2')
    expect(startingSubtitle(nextEpisodeSubtitles(off, undefined), decision, 18)).toBeUndefined()
    expect(startingSubtitle(nextEpisodeSubtitles(off, 'off'), decision, 18)).toBeUndefined()
  })
})
