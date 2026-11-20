import { describe, expect, it } from 'vitest'
import type { FilePlaybackState } from '../../player'
import { adBreaksWords, audioDetail, episodeCode, episodeLine, episodesOkHint, hintWords, nextButton, nextFill, nextMeta, qualityDetail, qualityRows, recordingLabel, recordingLine, subtitleDetail, subtitlePanel, type SubtitleChoices } from './labels'

const QUALITY_OPTIONS = [
  { id: 'auto', label: 'Automatic' }, { id: 'original', label: 'Original quality' }, { id: '2160', label: '4K · 20 Mbps' }, { id: '1080', label: '1080p · 8 Mbps' },
  { id: '720', label: '720p · 4 Mbps' }, { id: '480', label: '480p · 2 Mbps' }, { id: '360', label: '360p · 1 Mbps' },
]
const quality = (id: string, autoStep?: string): FilePlaybackState['quality'] => ({ id, options: QUALITY_OPTIONS, autoStep })

describe('the buttons’ words', () => {
  it('names episodes as the boards do', () => {
    expect(episodeCode(3, 7)).toBe('S03 E07')
    expect(episodeLine(3, 7, 'The Long Dark')).toBe('S03 · E07 · The Long Dark')
    expect(episodeLine(0, 2, '')).toBe('S00 · E02')
  })

  it('says what audio, subtitles and quality hold', () => {
    const audio = { options: [{ index: 1, label: 'English', detail: 'EAC3 5.1' }, { index: 2, label: 'Français' }], selected: 2 }
    expect(audioDetail(audio)).toBe('Français')
    expect(audioDetail({ ...audio, selected: undefined })).toBe('English')
    expect(subtitleDetail({ options: [{ id: 's1', label: 'English', burnIn: false }], selected: 's1' })).toBe('English')
    expect(subtitleDetail({ options: [], selected: undefined })).toBe('Off')
    expect(qualityDetail(quality('auto'))).toBe('Auto')
    expect(qualityDetail(quality('auto', 'original'))).toBe('Auto')
    expect(qualityDetail(quality('auto', '720'))).toBe('Auto · 720p')
    expect(qualityDetail(quality('original'))).toBe('Original')
    expect(qualityDetail(quality('2160'))).toBe('4K')
  })
})

describe('the Quality panel', () => {
  it('lists Automatic first, saying what it sends once picked, then Original and the steps with their bitrates', () => {
    const rows = qualityRows(quality('auto', '720'))
    expect(rows.map((r) => [r.label, r.detail])).toEqual([
      ['Automatic', 'Now 720p · 4 Mbps'], ['Original', 'Direct play when it can'], ['4K', '20 Mbps'], ['1080p', '8 Mbps'], ['720p', '4 Mbps'], ['480p', '2 Mbps'], ['360p', '1 Mbps'],
    ])
    expect(rows.filter((r) => r.checked).map((r) => r.id)).toEqual(['auto'])
    expect(qualityRows(quality('1080'))[0].detail).toBe('Original first, lower when it can’t keep up')
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

describe('the Next episode card', () => {
  const episode = { fileId: 2, episodeId: 20, showId: 3, showTitle: 'Harbor Lights', seasonNumber: 3, episodeNumber: 8, title: 'Ebb' }
  it('counts down with autoplay, filling as it goes, and waits for OK without', () => {
    expect(nextButton({ episode, heading: '', countdownMs: 7_000, seconds: 7 })).toBe('Next episode in 7')
    expect(nextFill({ episode, heading: '', countdownMs: 7_000, seconds: 7 })).toBeCloseTo(.3)
    expect(nextButton({ episode, heading: '' })).toBe('Play next episode')
    expect(nextFill({ episode, heading: '' })).toBe(0)
    expect(nextMeta(episode)).toBe('Harbor Lights')
    expect(nextMeta({ ...episode, seasonFinale: true })).toBe('Harbor Lights · season finale')
  })
})

describe('recordings', () => {
  it('says how much is written while it records', () => {
    expect(recordingLabel(24 * 60_000 + 59_000)).toBe('Recording · 24 min so far')
    expect(recordingLabel(30_000)).toBe('Recording · under a minute so far')
  })

  it('heads a recording with its episode and where it was recorded, or its channel and times', () => {
    const start = new Date(2026, 10, 17, 18, 0).toISOString(), end = new Date(2026, 10, 17, 18, 30).toISOString()
    expect(recordingLine({ number: '2.1', channelName: 'HRBR-HD', start, end }, true, 'en-US')).toBe('2.1 HRBR-HD · Tue 6:00–6:30 PM · recording')
    expect(recordingLine({ number: '4.2', channelName: 'SLTH', start, end, season: 3, episode: 5, episodeTitle: 'Lantern Street' }, false, 'en-US'))
      .toBe('S03 · E05 · Lantern Street · recorded Tue on 4.2')
  })

  it('says what was found of the ad breaks', () => {
    const breaks = [{ id: 1, kind: 'commercial' as const, startMs: 0, endMs: 1, final: true, confidence: 1, source: 'blackframe' as const }]
    expect(adBreaksWords(breaks, true)).toBe('Ad breaks are found once it has finished recording')
    expect(adBreaksWords([...breaks, { ...breaks[0], id: 2 }], false)).toBe('2 ad breaks found')
    expect(adBreaksWords(breaks, false)).toBe('1 ad break found')
    expect(adBreaksWords([], false)).toBeUndefined()
  })
})

describe('hints', () => {
  it('says what the keys do, for a file and a trailer; recordings have none', () => {
    expect(hintWords('episode', false)).toEqual({ seek: 'seek 10 s, hold for more', ok: 'plays or pauses, as Play/Pause does', back: 'closes a panel, then hides the controls, then leaves' })
    expect(hintWords('file', true)?.seek).toBe('10 s a press, held 30 s, 1 min, 2 min · seeks when you let go')
    expect(hintWords('trailer', false)?.back).toBe('hides the controls, then back to the movie')
    expect(hintWords('recording', false)).toBeUndefined()
    expect(episodesOkHint(true, false)).toBe('◀ ▶ changes season at once; ▼ goes to its episodes')
    expect(episodesOkHint(false, true)).toBe('Goes back to the video where it is')
    expect(episodesOkHint(false, false)).toBe('Plays it; your audio and subtitle choices carry over')
  })
})
