import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { fileNumbers, playSentence, soundName, speakerWords, titleTags, trackLine, versionLine, versionName, versionsLine } from './fileWords'

type MediaFileDetail = components['schemas']['MediaFileDetail']
type MovieDetail = components['schemas']['MovieDetail']
type PlaybackPlan = components['schemas']['PlaybackPlan']

function file(over: Partial<MediaFileDetail> = {}): MediaFileDetail {
  return {
    id: 1, libraryId: 1, path: '/m/x.mkv', relativePath: 'Ninety Fathoms (2026)/Ninety.Fathoms.2026.2160p.DV.mkv', size: 48.2 * 1024 ** 3,
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
        { index: 3, codec: 'subrip', language: 'eng', default: false, forced: false, image: false },
        { index: 4, codec: 'subrip', language: 'spa', default: false, forced: false, image: false },
        { index: 5, codec: 'hdmv_pgs_subtitle', language: 'fra', default: false, forced: false, image: true },
      ],
    },
    ...over,
  }
}

const hd = file({
  id: 2, relativePath: 'Ninety.Fathoms.1080p.mp4', size: 8 * 1024 ** 3, width: 1920, height: 1080, videoCodec: 'h264', hdr: '', dvProfile: undefined, audioCodec: 'eac3',
  probe: { container: 'mp4', formatName: 'mp4', durationMs: 1, video: [{ index: 0, codec: 'h264', width: 1920, height: 1080, default: true }], audio: [{ index: 1, codec: 'eac3', channels: 6, channelLayout: '5.1(side)', language: 'eng', default: true }], subtitles: [] },
})

function plan(over: Partial<PlaybackPlan> = {}): PlaybackPlan {
  return { fileId: 1, mode: 'direct', protocol: 'file', reasons: [], video: { copy: true, toneMap: false }, audio: { copy: true, index: 1 }, ...over }
}

describe('a title’s file in words', () => {
  it('names sound formats and speakers as people know them', () => {
    expect(soundName('truehd')).toBe('Dolby TrueHD')
    expect(soundName('eac3')).toBe('Dolby Digital Plus')
    expect(soundName('dts-hd')).toBe('DTS')
    expect(speakerWords(8, '7.1')).toBe('Surround 7.1')
    expect(speakerWords(6, '5.1(side)')).toBe('Surround 5.1')
    expect(speakerWords(2, 'stereo')).toBe('Stereo')
  })

  it('names versions by their picture', () => {
    expect(versionName(file())).toBe('4K with Dolby Vision')
    expect(versionName(hd)).toBe('1080p')
    expect(versionsLine([file(), hd])).toBe('4K with Dolby Vision, or 1080p')
  })

  it('puts three words about the picture and sound on the first screen', () => {
    const m = { quality: '4K', hdr: true, files: [file(), hd] } as Pick<MovieDetail, 'quality' | 'hdr' | 'files'>
    expect(titleTags(m)).toEqual(['4K', 'Dolby Vision', 'Surround sound'])
    expect(titleTags({ quality: '1080p', hdr: false, files: [file({ hdr: '', probe: { ...file().probe!, video: [], audio: [{ index: 1, codec: 'aac', channels: 2, language: 'eng', default: true }] } })] })).toEqual(['1080p'])
  })

  it('says the sound’s language and whether subtitles show', () => {
    expect(trackLine(file().probe)).toBe('English · subtitles off')
    const forced = { ...file().probe!, subtitles: [{ index: 3, codec: 'subrip', language: 'spa', default: false, forced: true, image: false }] }
    expect(trackLine(forced)).toBe('English · Spanish subtitles')
    expect(trackLine(undefined)).toBeUndefined()
  })

  it('says how it plays on this TV in a sentence, without codecs', () => {
    expect(playSentence(file(), plan())).toBe('It plays on this TV as it is, in 4K with Dolby Vision, without converting anything.')
    expect(playSentence(file(), plan({ mode: 'audio', audio: { copy: false, index: 1, codec: 'eac3', channels: 6 } })))
      .toBe('The picture plays on this TV as it is, in 4K with Dolby Vision. The sound is converted to surround this TV can play, because it can’t play Dolby TrueHD.')
    expect(playSentence(file(), plan({ mode: 'transcode', video: { copy: false, toneMap: true, width: 1920, height: 1080 }, audio: { copy: false, index: 1, codec: 'aac', channels: 2 } })))
      .toBe('The picture is converted to 1080p for this TV, in standard colour. The sound is converted to stereo this TV can play, because it can’t play Dolby TrueHD.')
    expect(playSentence(file(), plan({ mode: 'audio', audio: { copy: false, index: 1 } })))
      .toBe('The picture plays on this TV as it is, in 4K with Dolby Vision. The sound is converted so this TV can play it, because it can’t play Dolby TrueHD.')
    expect(playSentence(file(), plan({ mode: 'remux' }))).toBe('The picture plays on this TV as it is, in 4K with Dolby Vision. The sound plays as it is too.')
    expect(playSentence(file(), undefined)).toBe('How it plays on this TV is worked out when it starts.')
  })

  it('ends a version’s line with what it does', () => {
    expect(versionLine(file(), plan(), true)).toBe('Surround 7.1 · 48 GB · plays next')
    expect(versionLine(hd, plan({ fileId: 2 }), false)).toBe('Surround 5.1 · 8.0 GB · plays without converting anything')
    expect(versionLine(hd, plan({ mode: 'audio', audio: { copy: false, index: 1 } }), false)).toBe('Surround 5.1 · 8.0 GB · converts the sound')
  })

  it('keeps the numbers for File details', () => {
    expect(fileNumbers(file(), plan({ mode: 'audio', audio: { copy: false, index: 1, codec: 'eac3', channels: 6 } }))).toEqual([
      { label: 'Picture', value: 'HEVC · 3840 × 2160 · Dolby Vision profile 8' },
      { label: 'Sound', value: 'Dolby TrueHD 7.1, converted to Dolby Digital Plus 5.1' },
      { label: 'Subtitles', value: 'English and Spanish as text; French as pictures, drawn into the video when shown' },
      { label: 'File', value: 'Ninety.Fathoms.2026.2160p.DV.mkv · 48 GB' },
    ])
    expect(fileNumbers(hd, plan()).find((r) => r.label === 'Subtitles')?.value).toBe('None in the file')
  })
})
