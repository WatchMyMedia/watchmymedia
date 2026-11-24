import { describe, expect, it } from 'vitest'
import type { FilePlaybackState } from '../../player'
import {
  adBreaksWords, audioDetail, otherVersion, audioLine, creditsLine, episodeCode, episodeLine, episodesOkHint, nextButton, nextFill, nextKicker, nextLine, nextTitle, qualityDetail, qualityRows,
  qualitySentence, recordingLabel, recordingLine, startingWords, streamDetails, subtitleDetail, subtitleLine, subtitlePanel, timeWords, tracksDetail, type SubtitleChoices,
} from './labels'

const QUALITY_OPTIONS = [
  { id: 'auto', label: 'Automatic' }, { id: 'original', label: 'Original quality' }, { id: '2160', label: '4K · 20 Mbps' }, { id: '1080', label: '1080p · 8 Mbps' },
  { id: '720', label: '720p · 4 Mbps' }, { id: '480', label: '480p · 2 Mbps' }, { id: '360', label: '360p · 1 Mbps' },
]
const quality = (id: string, autoStep?: string): FilePlaybackState['quality'] => ({ id, options: QUALITY_OPTIONS, autoStep })

describe('the buttons’ words', () => {
  it('names episodes as the boards do', () => {
    expect(episodeCode(3, 7)).toBe('S03 E07')
    expect(episodeLine(3, 7, 'The Long Dark')).toBe('Season 3 · Episode 7 · The Long Dark')
    expect(episodeLine(0, 2, '')).toBe('Season 0 · Episode 2')
  })

  it('says what audio, subtitles and quality hold', () => {
    const audio = { options: [{ index: 1, label: 'English', detail: 'EAC3 5.1' }, { index: 2, label: 'Français' }], selected: 2 }
    expect(audioDetail(audio)).toBe('Français')
    expect(audioDetail({ ...audio, selected: undefined })).toBe('English')
    expect(subtitleDetail({ options: [{ id: 's1', label: 'English', burnIn: false }], selected: 's1' })).toBe('English')
    expect(subtitleDetail({ options: [], selected: undefined })).toBe('Off')
    expect(qualityDetail(quality('auto'))).toBe('Automatic')
    expect(qualityDetail(quality('auto', 'original'))).toBe('Automatic')
    expect(qualityDetail(quality('auto', '720'))).toBe('Automatic · 720p')
    expect(qualityDetail(quality('original'))).toBe('Original')
    expect(qualityDetail(quality('2160'))).toBe('4K')
    expect(tracksDetail(audio, { options: [], selected: undefined })).toBe('Français, off')
    expect(tracksDetail(audio, { options: [{ id: 's1', label: 'Español', burnIn: false }], selected: 's1' })).toBe('Français, Español')
  })

  it('names tracks in words, not formats', () => {
    expect(audioLine({ channels: 6 })).toBe('Surround sound')
    expect(audioLine({ channels: 8, title: 'TrueHD Atmos 7.1' })).toBe('Surround sound')
    expect(audioLine({ channels: 2 })).toBe('Stereo')
    expect(audioLine({ channels: 2, title: 'director’s commentary' })).toBe('Director’s commentary')
    expect(audioLine({ channels: 2, title: 'Audio Description' })).toBe('Describes what is on screen')
    expect(audioLine({})).toBeUndefined()
    expect(subtitleLine({ hearingImpaired: true, burnIn: false })).toBe('With sounds described')
    expect(subtitleLine({ forced: true, burnIn: false })).toBe('Only when another language is spoken')
    expect(subtitleLine({ burnIn: true })).toBe('Drawn into the picture; the video restarts from here')
    expect(subtitleLine({ burnIn: false })).toBeUndefined()
  })
})

describe('the Quality panel', () => {
  it('lists Automatic first, saying where it went down to once it did, then Original and the steps in words', () => {
    const rows = qualityRows(quality('auto', '720'))
    expect(rows.map((r) => [r.label, r.detail])).toEqual([
      ['Automatic', 'Now 720p for your connection'], ['Original', 'Always the file as it is'], ['4K', 'The sharpest the server makes'], ['1080p', 'Good on a slow connection'], ['720p', undefined], ['480p', undefined], ['360p', 'Smallest'],
    ])
    expect(rows.filter((r) => r.checked).map((r) => r.id)).toEqual(['auto'])
    expect(qualityRows(quality('1080'))[0].detail).toBe('Best picture the connection can hold')
  })

  it('opens with what is playing, in a sentence', () => {
    expect(qualitySentence({ mode: 'direct', video: '4K', quality: quality('auto') })).toBe('Playing the file as it is, in 4K. Nothing is being converted.')
    expect(qualitySentence({ mode: 'audio', video: '1080p', quality: quality('original') })).toBe('Playing the picture as it is, in 1080p. The sound is converted for this TV.')
    expect(qualitySentence({ mode: 'transcode', video: '720p', quality: quality('auto', '720') })).toBe('Converted to 720p for your connection.')
    expect(qualitySentence({ mode: undefined, quality: quality('auto') })).toBeUndefined()
  })

  it('keeps the numbers one press away, in Stream details', () => {
    const audio = { options: [{ index: 1, label: 'English', detail: 'EAC3 5.1' }], selected: 1 }
    expect(streamDetails({ mode: 'direct', video: '4K HDR', stream: 'Direct play · 4K HDR · EAC3 5.1', quality: quality('auto'), audio })).toEqual([
      { label: 'How it plays', value: 'Direct play' }, { label: 'Picture', value: '4K HDR' }, { label: 'Sound', value: 'EAC3 5.1' },
      { label: 'Quality', value: 'Automatic' }, { label: 'The whole line', value: 'Direct play · 4K HDR · EAC3 5.1' },
    ])
  })
})

describe('the Subtitles panel', () => {
  const own: SubtitleChoices['options'] = [
    { id: 's1', label: 'English', detail: undefined, burnIn: false },
    { id: 's3', label: 'Español', detail: 'burned in, restarts playback', burnIn: true },
    { id: 'os-1', label: 'English', detail: 'OpenSubtitles', burnIn: false, source: 'opensubtitles' },
  ]
  const online = (o: Partial<NonNullable<SubtitleChoices['online']>> = {}): NonNullable<SubtitleChoices['online']> => ({
    available: true, canDownload: true, remaining: 17, attribution: 'whatever the server says',
    results: [{ id: 'r1', label: 'English', detail: 'matches this file', trackId: 'os-1' }, { id: 'r2', label: 'Español', detail: 'HI' }], ...o,
  })

  it('has Off and the file’s own, and no OpenSubtitles where the server has none', () => {
    const panel = subtitlePanel({ options: own.slice(0, 2), selected: undefined, online: online({ available: false }) })
    expect(panel.off.checked).toBe(true)
    expect(panel.own.map((r) => r.id)).toEqual(['s1', 's3'])
    expect(panel.online).toBeUndefined()
  })

  it('puts downloaded tracks under OpenSubtitles, leaves out results already downloaded, and credits it in our words with the downloads left', () => {
    const panel = subtitlePanel({ options: own, selected: 'os-1', online: online() })
    expect(panel.own.map((r) => r.id)).toEqual(['s1', 's3'])
    expect(panel.online?.downloaded).toEqual([{ id: 'os-1', label: 'English', detail: 'OpenSubtitles', checked: true }])
    expect(panel.online?.results).toEqual([{ id: 'r2', label: 'Español', detail: 'HI', state: 'get' }])
    expect(panel.online?.credit).toBe('Subtitles provided by OpenSubtitles.com · 17 downloads left today')
    expect(panel.online?.note).toBeUndefined()
  })

  it('shows one downloading and holds the rest, and says why nothing can be downloaded', () => {
    expect(subtitlePanel({ options: [], selected: undefined, online: online({ downloading: 'r2' }) }).online?.results.map((r) => r.state)).toEqual(['off', 'busy'])
    expect(subtitlePanel({ options: [], selected: undefined, online: online({ remaining: 0 }) }).online).toMatchObject({ note: 'No downloads left today.', credit: 'Subtitles provided by OpenSubtitles.com' })
    expect(subtitlePanel({ options: [], selected: undefined, online: online({ canDownload: false }) }).online?.note).toBe('Only admins can download subtitles on this server.')
    expect(subtitlePanel({ options: [], selected: undefined, online: online({ results: [] }) }).online?.note).toBe('Nothing found online in these languages.')
    expect(subtitlePanel({ options: [], selected: undefined, online: online({ error: 'The search for subtitles online failed.' }) }).online?.note).toBe('The search for subtitles online failed.')
  })
})

describe('Up next', () => {
  const episode = { fileId: 2, episodeId: 20, showId: 3, showTitle: 'Harbor Lights', seasonNumber: 3, episodeNumber: 8, title: 'Ebb', runtimeMin: 50 }
  it('says when it starts, what it is, how long and when it would end', () => {
    expect(nextKicker({ seconds: 12 })).toBe('Up next · starts in 12 seconds')
    expect(nextKicker({ seconds: 1 })).toBe('Up next · starts in 1 second')
    expect(nextKicker({})).toBe('Up next')
    expect(nextTitle(episode)).toBe('Episode 8 · Ebb')
    expect(nextTitle({ ...episode, seasonFinale: true })).toBe('Episode 8 · Ebb · season finale')
    expect(nextLine(episode, new Date(2026, 10, 23, 20, 46).getTime(), 'en-US')).toBe('50 min · ends at 9:36 PM')
    expect(nextLine({ runtimeMin: undefined }, 0)).toBeUndefined()
    expect(creditsLine('Harbor Lights', 'S3 E7 · The Long Dark')).toBe('Harbor Lights · S3 E7 · The Long Dark · credits')
    expect(nextButton({ episode, heading: '', countdownMs: 7_000, seconds: 7 })).toBe('Next episode in 7')
    expect(nextFill({ episode, heading: '', countdownMs: 7_000, seconds: 7 })).toBeCloseTo(.3)
    expect(nextFill({ episode, heading: '' })).toBe(0)
  })
})

describe('the time over the bar', () => {
  const now = new Date(2026, 10, 23, 20, 12).getTime()
  const MIN = 60_000
  it('says what is left and when it ends; while seeking, what is left from there', () => {
    expect(timeWords({ positionMs: 18 * MIN, durationMs: 47 * MIN, timelineMs: 47 * MIN, recordingNow: false }, now, 'en-US')).toEqual({ strong: '29 min left', soft: 'Ends at 8:41 PM' })
    expect(timeWords({ positionMs: 18 * MIN, durationMs: 47 * MIN, timelineMs: 47 * MIN, targetMs: 24 * MIN, recordingNow: false }, now, 'en-US')).toEqual({ strong: '23 min left from there' })
    expect(timeWords({ positionMs: 0, durationMs: 0, timelineMs: 0, recordingNow: false }, now)).toBeUndefined()
  })

  it('says how far a recording has got and when it ends, while it records', () => {
    expect(timeWords({ positionMs: 8 * MIN, durationMs: 23 * MIN, timelineMs: 30 * MIN, recordingNow: true }, now, 'en-US')).toEqual({ strong: 'Recorded up to 8:12 PM', soft: 'It ends at 8:19 PM' })
  })

  it('says where a start lands', () => {
    expect(startingWords(18 * MIN + 2000, 47 * MIN)).toEqual({ title: 'Starting where you left off', line: '18 minutes in · 29 min left' })
    expect(startingWords(MIN, 0)).toEqual({ title: 'Starting where you left off', line: '1 minute in' })
    expect(startingWords(20_000, 47 * MIN)).toEqual({ title: 'Starting' })
  })
})

describe('recordings', () => {
  it('says how much is written while it records', () => {
    expect(recordingLabel(24 * 60_000 + 59_000)).toBe('Recording · 24 min so far')
    expect(recordingLabel(30_000)).toBe('Recording · under a minute so far')
  })

  it('heads a recording with its episode and where it was recorded, or its channel and times', () => {
    const start = new Date(2026, 10, 17, 18, 0).toISOString(), end = new Date(2026, 10, 17, 18, 30).toISOString()
    const sameDay = new Date(2026, 10, 17, 18, 23).getTime(), later = new Date(2026, 10, 20, 9, 0).getTime()
    expect(recordingLine({ number: '2.1', channelName: 'HRBR', start, end }, 'en-US', sameDay)).toBe('2.1 HRBR · today, 6:00 – 6:30 PM')
    expect(recordingLine({ number: '4.2', channelName: 'SLTH', start, end, season: 3, episode: 5, episodeTitle: 'Lantern Street' }, 'en-US', later))
      .toBe('Season 3 · Episode 5 · Lantern Street · recorded Tuesday on 4.2')
  })

  it('says what was found of the ad breaks', () => {
    const breaks = [{ id: 1, kind: 'commercial' as const, startMs: 0, endMs: 1, final: true, confidence: 1, source: 'blackframe' as const }]
    expect(adBreaksWords(breaks, true)).toBe('Ad breaks can be skipped once it has finished recording.')
    expect(adBreaksWords([...breaks, { ...breaks[0], id: 2 }], false)).toBe('2 ad breaks found')
    expect(adBreaksWords(breaks, false)).toBe('1 ad break found')
    expect(adBreaksWords([], false)).toBeUndefined()
  })
})

describe('the Episodes panel', () => {
  it('says what OK does there', () => {
    expect(episodesOkHint(true, false)).toBe('◀ ▶ changes season at once; ▼ goes to its episodes')
    expect(episodesOkHint(false, true)).toBe('Goes back to the video where it is')
    expect(episodesOkHint(false, false)).toBe('Plays it; your audio and subtitle choices carry over')
  })
})

describe('when it can’t play', () => {
  it('offers the largest other version smaller than the one that failed, else the nearest larger, and none when there is none', () => {
    const files = [{ id: 1, width: 3840, height: 2160 }, { id: 2, width: 1920, height: 1080 }, { id: 3, width: 1280, height: 720 }, { id: 4, height: 480, width: 640, status: 'missing' }]
    expect(otherVersion(files, 1)).toEqual({ fileId: 2, label: '1080p' })
    expect(otherVersion(files, 3)).toEqual({ fileId: 2, label: '1080p' })
    expect(otherVersion([{ id: 1 }, { id: 5 }], 1)).toEqual({ fileId: 5, label: 'other' })
    expect(otherVersion([{ id: 1 }], 1)).toBeUndefined()
    expect(otherVersion(undefined, 1)).toBeUndefined()
  })
})
