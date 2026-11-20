import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import {
  castLabel, channelsText, fileName, mainLabel, movieFacts, movieTags, moviePlay, movieVersions, partway, planLine, progressOf, remaining,
  resumeVersion, trailerPlay, versionLine, versionTags, versionTitle, versionTracks,
} from './movieLabels'

type MediaFileDetail = components['schemas']['MediaFileDetail']
type MovieDetail = components['schemas']['MovieDetail']
type PlaybackPlan = components['schemas']['PlaybackPlan']

function file(over: Partial<MediaFileDetail> = {}): MediaFileDetail {
  return {
    id: 1, libraryId: 1, path: '/m/x.mkv', relativePath: 'Dead Reckoning Bay (2026)/Dead.Reckoning.Bay.2026.2160p.DV.mkv', size: 48.2 * 1024 ** 3,
    modifiedAt: '', status: 'present', parsed: {} as MediaFileDetail['parsed'], firstSeenAt: '', addedAt: '', lastSeenAt: '', matchStatus: 'matched',
    matchLocked: false, matchIgnored: false, width: 3840, height: 2160, videoCodec: 'hevc', hdr: 'dolbyvision', dvProfile: 8,
    probe: {
      container: 'matroska', formatName: 'matroska', durationMs: 8_040_000,
      video: [{ index: 0, codec: 'hevc', width: 3840, height: 2160, default: true, hdr: 'dolbyvision', dvProfile: 8 }],
      audio: [
        { index: 1, codec: 'truehd', channels: 8, channelLayout: '7.1', language: 'eng', default: true },
        { index: 2, codec: 'ac3', channels: 2, channelLayout: 'stereo', language: 'eng', title: 'Commentary', default: false },
      ],
      subtitles: [
        { index: 3, codec: 'subrip', language: 'eng', title: 'SDH', default: false, forced: false, image: false },
        { index: 4, codec: 'hdmv_pgs_subtitle', language: 'eng', default: false, forced: false, image: true },
      ],
    },
    ...over,
  }
}

function movie(over: Partial<MovieDetail> = {}): MovieDetail {
  return {
    id: 102, libraryId: 1, tmdbId: 9, title: 'Dead Reckoning Bay', overview: '', genres: ['Thriller', 'Mystery', 'Drama'], fileCount: 2, credits: [],
    files: [file(), file({ id: 2, relativePath: 'Dead.Reckoning.Bay.1080p.mp4', width: 1920, height: 1080, videoCodec: 'h264', hdr: '', dvProfile: undefined, probe: undefined, audioCodec: 'aac' })],
    hdr: true, totalSize: 0, year: 2026, runtimeMin: 134, contentRating: 'R', rating: 7.62, quality: '4K',
    ...over,
  }
}

const watch = (positionMs: number, durationMs: number, completed = false, fileId?: number) => ({ positionMs, durationMs, completed, updatedAt: '', fileId })

describe('the movie header', () => {
  it('resumes a movie partway through, with what is left', () => {
    const m = movie({ watch: watch(32 * 60_000, 104 * 60_000) })
    expect(mainLabel(m)).toBe('Resume · 1 h 12 min left')
    expect(progressOf(m)).toBeCloseTo(32 / 104)
    expect(mainLabel(movie())).toBe('Play')
    expect(partway(movie({ watch: watch(1000, 5000, true) }))).toBeUndefined()
  })

  it('words what is left in hours and minutes, at least a minute', () => {
    expect(remaining(72 * 60_000)).toBe('1 h 12 min left')
    expect(remaining(60 * 60_000)).toBe('1 h left')
    expect(remaining(10_000)).toBe('1 min left')
  })

  it('lists the facts and the default version’s tags', () => {
    expect(movieFacts(movie())).toEqual(['2026', '2 h 14 min', 'Thriller, Mystery', 'R', '★ 7.6'])
    expect(movieTags(movie())).toEqual(['4K', 'Dolby Vision', '7.1'])
    expect(movieTags(movie({ files: [], hdr: false, quality: '1080p' }))).toEqual(['1080p'])
  })
})

describe('versions', () => {
  it('names a version by its size and HDR, else its file type', () => {
    expect(versionTitle(file())).toBe('4K Dolby Vision')
    expect(versionTitle(file({ width: 1920, height: 1080, hdr: '', probe: undefined }))).toBe('1080p')
    expect(versionTitle(file({ width: undefined, height: undefined, hdr: '', probe: undefined, relativePath: 'a/b.mkv' }))).toBe('MKV')
  })

  it('tags the container, codecs, Dolby Vision profile, audio and size', () => {
    expect(versionTags(file())).toEqual(['MKV', 'HEVC', 'Dolby Vision · P8', 'TrueHD 7.1', '48 GB'])
    expect(versionTags(movie().files[1])).toEqual(['MP4', 'H.264', 'AAC', '48 GB'])
  })

  it('leaves out missing files', () => {
    expect(movieVersions(movie({ files: [file({ status: 'missing' }), file({ id: 5 })] })).map((f) => f.id)).toEqual([5])
  })

  it('lists the tracks, a few of each, the default and burn-in marked', () => {
    expect(versionTracks(file())).toEqual([
      { kind: 'Audio', text: 'English · TrueHD 7.1', default: true },
      { kind: undefined, text: 'Commentary · AC3 2.0', default: false },
      { kind: 'Subtitles', text: 'English SDH · SRT', burnIn: false },
      { kind: undefined, text: 'English · PGS', burnIn: true },
    ])
    const many = file({ probe: { ...file().probe!, audio: Array.from({ length: 5 }, (_, i) => ({ index: i, codec: 'aac', channels: 6, default: i === 0, language: 'eng' })), subtitles: [] } })
    expect(versionTracks(many).map((l) => l.text)).toEqual(['English · AAC 5.1', 'English · AAC 5.1', 'English · AAC 5.1', '+ 2 more'])
    expect(versionTracks(file({ probe: undefined, audioCodec: 'eac3' }))).toEqual([{ kind: 'Audio', text: 'EAC3', default: true }])
  })

  it('reads channels from the layout first, then the count', () => {
    expect(channelsText(6, '5.1(side)')).toBe('5.1')
    expect(channelsText(2, 'stereo')).toBe('2.0')
    expect(channelsText(8)).toBe('7.1')
    expect(channelsText(10)).toBe('10 ch')
    expect(channelsText(undefined)).toBeUndefined()
  })
})

describe('how a version would play', () => {
  const plan = (over: Partial<PlaybackPlan>): PlaybackPlan => ({ fileId: 1, mode: 'direct', protocol: 'file', reasons: [], video: { copy: true, toneMap: false }, audio: { index: 1, copy: true }, ...over })

  it('says direct play, a remux with its audio, or a transcode, on this TV', () => {
    expect(planLine(plan({}))).toBe('direct play')
    expect(planLine(plan({ mode: 'audio', protocol: 'hls', audio: { index: 1, copy: false, codec: 'eac3' } }))).toBe('remux to fMP4, audio to EAC3 on this TV')
    expect(planLine(plan({ mode: 'remux', protocol: 'hls' }))).toBe('remux to fMP4 on this TV')
    expect(planLine(plan({ mode: 'transcode', protocol: 'hls', video: { copy: false, toneMap: true, codec: 'h264', width: 1920, height: 1080 }, audio: { index: 1, copy: false, codec: 'aac', channels: 6 } })))
      .toBe('transcode to 1080p H.264, audio to AAC 5.1 on this TV')
    expect(planLine(undefined)).toBeUndefined()
  })

  it('puts it after the file’s name', () => {
    expect(versionLine(file(), plan({}))).toBe('Dead.Reckoning.Bay.2026.2160p.DV.mkv · direct play')
    expect(versionLine(file(), undefined)).toBe('Dead.Reckoning.Bay.2026.2160p.DV.mkv')
    expect(fileName('a\\b\\c.mkv')).toBe('c.mkv')
  })
})

describe('playing', () => {
  it('resumes the version the position is on, with no start, so the server resumes a little before', () => {
    const m = movie({ watch: watch(5000, 90_000, false, 2) })
    expect(resumeVersion(m)?.id).toBe(2)
    expect(resumeVersion(movie())?.id).toBe(1)
    expect(moviePlay(m, { id: 2 })).toEqual({ kind: 'file', fileId: 2, startMs: undefined, title: 'Dead Reckoning Bay', subtitle: '2026', backdropPath: undefined })
    expect(moviePlay(m, { id: 2 }, 0)).toMatchObject({ startMs: 0 })
  })

  it('plays a trailer file in the player and a YouTube trailer in the trailer screen', () => {
    expect(trailerPlay(movie({ trailers: [{ source: 'file', fileId: 77, name: 'Trailer' }, { source: 'youtube', key: 'abcdefghijk', name: 'Official' }] })))
      .toEqual({ kind: 'trailer', movieId: 102, fileId: 77, title: 'Dead Reckoning Bay' })
    expect(trailerPlay(movie({ trailers: [{ source: 'vimeo', name: 'x' }, { source: 'youtube', key: 'abcdefghijk', name: 'Official' }] })))
      .toEqual({ kind: 'trailer', movieId: 102, youtubeKey: 'abcdefghijk', title: 'Dead Reckoning Bay' })
    expect(trailerPlay(movie({ trailers: [] }))).toBeUndefined()
  })

  it('names a cast member with their part', () => {
    expect(castLabel({ name: 'Ida Markham', character: 'Nell Kerr' })).toBe('Ida Markham as Nell Kerr')
    expect(castLabel({ name: 'Ida Markham' })).toBe('Ida Markham')
  })
})
