import { describe, expect, it } from 'vitest'
import {
  channelWords,
  codecName,
  episodeRowFacts,
  spoilerHidden,
  hasTrackChoice,
  episodeRowState,
  filesSummary,
  historyWords,
  initials,
  planWords,
  playChoices,
  qualityLine,
  seasonLine,
  seasonTabWords,
  showState,
  sizeAndRate,
  soundOptions,
  subtitleOptions,
  versionName,
  versionTags,
  yourPlace,
} from './titlePage'

const probe = {
  bitRate: 24_000_000,
  audio: [
    { index: 1, codec: 'eac3', channels: 6, channelLayout: '5.1(side)', language: 'eng', default: true, bitRate: 640_000 },
    { index: 2, codec: 'aac', channels: 2, language: 'eng', title: 'Director’s commentary', default: false },
    { index: 3, codec: 'aac', channels: 2, language: 'spa', default: false },
  ],
  subtitles: [
    { index: 4, codec: 'subrip', language: 'eng', forced: false, image: false, default: false },
    { index: 5, codec: 'hdmv_pgs_subtitle', language: 'eng', forced: true, image: true, default: false },
  ],
} as never

describe('codec and channel words', () => {
  it('writes codecs as people know them', () => {
    expect(codecName('h264')).toBe('H.264')
    expect(codecName('hevc')).toBe('HEVC')
    expect(codecName('weird')).toBe('WEIRD')
    expect(codecName(undefined)).toBe('')
  })

  it('names channel layouts', () => {
    expect(channelWords(6, '5.1(side)')).toBe('5.1')
    expect(channelWords(2)).toBe('stereo')
    expect(channelWords(1)).toBe('mono')
    expect(channelWords(8)).toBe('7.1')
    expect(channelWords(3)).toBe('3 channels')
    expect(channelWords(undefined, 'stereo')).toBe('stereo')
  })
})

describe('versions', () => {
  it('names a version by its picture', () => {
    expect(versionName({ height: 2160, hdr: 'hdr10', relativePath: 'a.mkv' })).toBe('4K HDR')
    expect(versionName({ height: 2160, hdr: 'dolbyvision', relativePath: 'a.mkv' })).toBe('4K Dolby Vision')
    expect(versionName({ height: 1080, relativePath: 'a.mp4' })).toBe('1080p')
    expect(versionName({ relativePath: 'a.mp4' })).toBe('MP4')
  })

  it('says the picture and sound in one line, only past the usual', () => {
    const sound = (channels: number, channelLayout?: string) => ({ audio: [{ index: 1, codec: 'truehd', channels, channelLayout, default: true }] }) as never
    expect(qualityLine({ height: 2160, hdr: 'dolbyvision', probe: sound(8, '7.1') })).toBe('In 4K with Dolby Vision and 7.1 sound')
    expect(qualityLine({ height: 2160, hdr: 'hdr10', probe })).toBe('In 4K with HDR and 5.1 sound')
    expect(qualityLine({ width: 3840, height: 1600, hdr: 'hlg' })).toBe('In 4K with HDR')
    expect(qualityLine({ height: 2160, probe: sound(2) })).toBe('In 4K')
    expect(qualityLine({ height: 1080, probe })).toBe('In 1080p with 5.1 sound')
    expect(qualityLine({ height: 1080, audioChannels: 6 })).toBe('In 1080p with 5.1 sound')
    expect(qualityLine({ height: 720, probe: sound(3) })).toBe('In 720p with surround sound')
    expect(qualityLine({ height: 480, probe: sound(6) })).toBe('With 5.1 sound')
    expect(qualityLine({ hdr: 'dolbyvision' })).toBe('With Dolby Vision')
    expect(qualityLine({ height: 1080, probe: sound(2) })).toBe('')
    expect(qualityLine({})).toBe('')
  })

  it('lists the technical tags and the size and rate', () => {
    expect(versionTags({ height: 2160, videoCodec: 'hevc', hdr: 'hdr10', relativePath: 'a.mkv', size: 1, probe })).toEqual(['2160p', 'HEVC', 'HDR10', 'EAC3 5.1', 'MKV'])
    expect(versionTags({ height: 720, videoCodec: 'h264', audioCodec: 'aac', relativePath: 'a.mp4', size: 1 })).toEqual(['720p', 'H.264', 'AAC', 'MP4'])
    expect(sizeAndRate({ size: 21.4 * 1024 ** 3, probe })).toBe('21 GB · 24 Mb/s')
    expect(sizeAndRate({ size: 1024 ** 3, durationMs: 1_000_000 })).toBe('1.0 GB · 8.6 Mb/s')
  })

  it('sums the files for the closed File details', () => {
    expect(filesSummary([{ size: 20 * 1024 ** 3, probe }, { size: 6 * 1024 ** 3 }])).toBe('2 versions · 26 GB · sound in 2 languages · 2 subtitle tracks')
    expect(filesSummary([{ size: 0 }])).toBe('1 version')
  })
})

describe('tracks', () => {
  it('words sound tracks, naming commentary', () => {
    expect(soundOptions(probe).map((o) => [o.index, o.label, o.default])).toEqual([
      [1, 'English · 5.1', true],
      [2, 'English · Commentary', false],
      [3, 'Spanish · stereo', false],
    ])
    expect(soundOptions(probe)[0].detail).toBe('EAC3 · 5.1 · 640 kb/s')
    expect(soundOptions(undefined)).toEqual([])
    expect(soundOptions({ audio: [{ index: 1, codec: 'aac', channels: 2, language: 'und' }] } as never)[0].label).toBe('Stereo')
    const twins = soundOptions({ audio: [{ index: 1, codec: 'aac', channels: 2, language: 'eng' }, { index: 2, codec: 'aac', channels: 2, language: 'eng' }] } as never)
    expect(twins.map((o) => o.label)).toEqual(['English · stereo (track 1)', 'English · stereo (track 2)'])
  })

  it('words subtitle tracks with the id the player asks for', () => {
    expect(subtitleOptions(probe)).toEqual([
      { id: 's4', label: 'English', detail: 'Text (SubRip)', image: false },
      { id: 's5', label: 'English · Forced', detail: 'Picture (PGS)', image: true },
    ])
  })

  it('carries only the choices made into the player’s address', () => {
    expect(playChoices({})).toEqual({})
    expect(playChoices({ audio: 2, subtitle: 'off' })).toEqual({ audio: 2, subtitle: 'off' })
    expect(playChoices({ audio: 0, subtitle: '' })).toEqual({ audio: 0 })
  })
})

describe('how it plays here', () => {
  it('says it in words', () => {
    expect(planWords({ mode: 'direct' } as never)).toBe('Plays on this browser without converting')
    expect(planWords({ mode: 'remux' } as never)).toBe('Plays on this browser without converting')
    expect(planWords({ mode: 'audio' } as never)).toBe('Plays on this browser; the sound is converted as it plays')
    expect(planWords({ mode: 'transcode', video: { copy: false } } as never, { videoCodec: 'hevc' })).toBe('This browser can’t play HEVC here, so the video is converted as it plays')
    expect(planWords({ mode: 'transcode', video: { copy: true } } as never)).toBe('The video is converted as it plays')
    expect(planWords(undefined)).toBeUndefined()
  })
})

describe('who watched', () => {
  it('says where you are in a movie', () => {
    expect(yourPlace({ completed: true, positionMs: 0 })).toBe('You watched it all')
    expect(yourPlace({ completed: false, positionMs: 66 * 60_000 })).toBe('You are 1 h 6 min in')
    expect(yourPlace({ completed: false, positionMs: 30_000 })).toBeUndefined()
    expect(yourPlace(undefined)).toBeUndefined()
  })

  it('words a play in the history', () => {
    const movie = { type: 'movie' }
    expect(historyWords({ completed: true, positionMs: 0, item: movie, user: { name: 'Jordan' } } as never, false)).toBe('Jordan watched it all')
    expect(historyWords({ completed: false, positionMs: 40 * 60_000, item: movie, user: { name: 'Sam' } } as never, false)).toBe('Sam stopped at 40 min')
    const ep = { type: 'episode', seasonNumber: 3, episodeNumber: 7 }
    expect(historyWords({ completed: true, positionMs: 0, item: ep, user: { name: 'Robin' } } as never, true)).toBe('You watched S3 E7')
    expect(historyWords({ completed: false, positionMs: 0, item: ep, user: { name: 'Robin' } } as never, true)).toBe('You stopped in S3 E7')
  })
})

describe('seasons and episodes', () => {
  it('counts a season’s tab', () => {
    expect(seasonTabWords({ episodeCount: 10, availableCount: 10, watchedCount: 10 }, false)).toBe('10 · all watched')
    expect(seasonTabWords({ episodeCount: 8, availableCount: 8, watchedCount: 6 }, false)).toBe('6 of 8 watched')
    expect(seasonTabWords({ episodeCount: 2, availableCount: 2, watchedCount: 0 }, false)).toBe('2')
    expect(seasonTabWords({ episodeCount: 10, availableCount: 4, watchedCount: 0 }, true)).toBe('4 of 10')
    expect(seasonTabWords({ episodeCount: 10, availableCount: 0, watchedCount: 0 }, true)).toBe('not in library')
  })

  it('says how much of a season is here and what airs next', () => {
    const e = (number: number, over: object = {}) => ({ number, hasFile: true, isNew: false, aired: true, airDate: '2026-10-01', ...over })
    expect(seasonLine([e(1), e(2, { isNew: true }), e(3, { hasFile: false, aired: false, airDate: '2026-12-01' })] as never)).toMatch(/^2 episodes here · 1 new · episode 3 airs /)
    expect(seasonLine([e(1)] as never)).toBe('1 episode here')
  })

  it('words an episode row', () => {
    const files = [{ durationMs: 50 * 60_000 }]
    expect(episodeRowFacts({ aired: true, airDate: '2026-10-05', files, hasFile: true } as never)).toMatch(/^Aired .* · 50 min$/)
    expect(episodeRowFacts({ aired: false, hasFile: false, files: [] } as never)).toBe('Not aired yet')
    expect(episodeRowState({ hasFile: true, files, watch: { completed: true, positionMs: 0 } } as never)).toEqual({ text: 'Watched', gold: false })
    expect(episodeRowState({ hasFile: true, files, watch: { completed: false, positionMs: 21 * 60_000, durationMs: 50 * 60_000 } } as never)).toEqual({ text: '29 min left', gold: true })
    expect(episodeRowState({ hasFile: true, files, isNew: true } as never)).toEqual({ text: 'Not watched', gold: false })
    expect(episodeRowState({ hasFile: false, files: [] } as never)).toBeUndefined()
  })

  it('says where a show stands', () => {
    expect(showState({ status: 'Ended' })).toEqual({ label: 'Ended', gold: false })
    expect(showState({ status: 'Returning Series', nextAirDate: '2026-11-30' })?.label).toMatch(/^Continuing · next episode /)
    expect(showState({ status: 'Returning Series' })).toEqual({ label: 'Continuing', gold: true })
    expect(showState({})).toBeUndefined()
  })

  it('makes initials', () => {
    expect(initials('Ines Calloway')).toBe('IC')
    expect(initials('Cher')).toBe('C')
    expect(initials('Mary Ann de Vries')).toBe('MV')
  })
})

describe('spoilerHidden', () => {
  const watched = { watch: { completed: true, positionMs: 0, durationMs: 1 } }
  const started = { watch: { completed: false, positionMs: 60_000, durationMs: 1 } }
  const fresh = {}
  it('hides only what the person has not watched or started, and only when asked', () => {
    expect(spoilerHidden(fresh as never, true)).toBe(true)
    expect(spoilerHidden(watched as never, true)).toBe(false)
    expect(spoilerHidden(started as never, true)).toBe(false)
    expect(spoilerHidden(fresh as never, false)).toBe(false)
  })
})

describe('hasTrackChoice', () => {
  it('is true for two sound tracks or any subtitle, false for one track alone', () => {
    const a = (index: number) => ({ index, codec: 'aac', channels: 2 })
    expect(hasTrackChoice({ audio: [a(1), a(2)], subtitles: [] } as never)).toBe(true)
    expect(hasTrackChoice({ audio: [a(1)], subtitles: [{ index: 3, codec: 'subrip' }] } as never)).toBe(true)
    expect(hasTrackChoice({ audio: [a(1)], subtitles: [] } as never)).toBe(false)
    expect(hasTrackChoice(undefined)).toBe(false)
  })
})
