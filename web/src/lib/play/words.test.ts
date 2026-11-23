import { describe, expect, it } from 'vitest'
import {
  audioLine,
  chosenSubtitle,
  audioWords,
  autoWords,
  channelWords,
  episodeName,
  lastWithFile,
  mbps,
  onlineWords,
  parseVolume,
  playbackRows,
  playerTitle,
  previousInSeason,
  previousSeason,
  reasonWords,
  segmentMarks,
  stepVolume,
  streamStory,
  subtitleWords,
  timeLeftLine,
  videoLine,
  volumeLabel,
} from './words'

const video = { codec: 'h264', copy: true, width: 1920, height: 1080, toneMap: false }
const audio = { codec: 'aac', copy: true, index: 1, channels: 2 }

describe('the player’s title', () => {
  it('puts a show over its episode', () => {
    expect(playerTitle('Harbor Lights · S03E07 The Long Dark')).toEqual({ kicker: 'Harbor Lights', heading: 'S3 E7 · The Long Dark' })
    expect(playerTitle('Harbor Lights · S03E07')).toEqual({ kicker: 'Harbor Lights', heading: 'S3 E7' })
    expect(playerTitle('Night Shift · S01E02-E03 Double')).toEqual({ kicker: 'Night Shift', heading: 'S1 E2 · Double' })
  })

  it('keeps a movie’s title, dropping a year after it', () => {
    expect(playerTitle('Paper Moons')).toEqual({ heading: 'Paper Moons' })
    expect(playerTitle('Paper Moons · 2021')).toEqual({ heading: 'Paper Moons' })
    expect(playerTitle('Paper Moons · Trailer')).toEqual({ kicker: 'Paper Moons', heading: 'Trailer' })
    expect(playerTitle(undefined)).toEqual({ heading: '' })
  })
})

describe('the stream’s story', () => {
  it('says direct play plainly', () => {
    expect(streamStory({ mode: 'direct', reasons: [], video, audio })).toBe('Plays on this browser without converting.')
  })

  it('says why the video is converted, and what happens to the sound', () => {
    expect(streamStory({ mode: 'transcode', reasons: ['client cannot decode hevc video'], video: { ...video, copy: false, toneMap: true }, audio })).toBe(
      'Converting the video for this browser, turning its HDR into SDR, because this browser can’t play HEVC video. Sound is untouched.',
    )
    expect(streamStory({ mode: 'transcode', reasons: ['client cannot decode hevc10 video', 'client cannot decode dts audio'], video: { ...video, copy: false }, audio: { ...audio, copy: false } })).toBe(
      'Converting the video for this browser, because this browser can’t play 10-bit HEVC video and this browser can’t play DTS sound. Sound is converted to AAC stereo.',
    )
  })

  it('words a repackage and a sound-only conversion', () => {
    expect(streamStory({ mode: 'remux', reasons: ['client cannot play the mkv container'], video, audio })).toBe(
      'Plays without converting the picture or sound: the server repackages the file, because this browser can’t open MKV files.',
    )
    expect(streamStory({ mode: 'audio', reasons: ['client cannot decode dts audio'], video, audio: { ...audio, copy: false } })).toBe(
      'Converting the sound only, because this browser can’t play DTS sound. The picture is untouched.',
    )
  })

  it('leaves out reasons it can’t word', () => {
    expect(reasonWords('something new')).toBeUndefined()
    expect(reasonWords('1920x1080 exceeds the client\'s 1280x720')).toBe('the quality chosen is smaller than the file')
    expect(reasonWords('6 audio channels exceed the client\'s 2')).toBe('this browser plays up to stereo sound')
    expect(reasonWords('the screen cannot show HDR10')).toBe('this screen can’t show HDR10')
    expect(streamStory({ mode: 'transcode', reasons: ['something new'], video: { ...video, copy: false }, audio })).toBe('Converting the video for this browser. Sound is untouched.')
  })
})

describe('Playback info’s numbers', () => {
  it('says the video from the file to what plays', () => {
    expect(videoLine({ video })).toBe('1080p H.264 · as it is')
    expect(videoLine({ video: { ...video, copy: true, hdr: 'hdr10', codec: 'hevc', height: 2160, width: 3840 } })).toBe('4K HEVC, HDR10 · as it is')
    expect(videoLine({ video: { ...video, copy: false, toneMap: true } }, { codec: 'hevc', bitDepth: 10, width: 3840, height: 2160, hdr: 'hdr10' })).toBe('4K 10-bit HEVC, HDR10 → 1080p H.264, tone-mapped')
    expect(videoLine({ video: { ...video, copy: false } })).toBe('1080p H.264')
  })

  it('says the sound', () => {
    const tracks = [{ index: 1, codec: 'eac3', channels: 6, language: 'eng' }]
    expect(audioLine({ audio: { codec: 'eac3', copy: true, index: 1, channels: 6 }, audioTracks: tracks })).toBe('English 5.1, E-AC-3 · as it is')
    expect(audioLine({ audio: { codec: 'aac', copy: false, index: 1, channels: 2 }, audioTracks: [{ index: 1, codec: 'dts', channels: 6, language: 'und' }] })).toBe('5.1, DTS → Stereo AAC')
  })

  it('lists the rows in order', () => {
    const rows = playbackRows({
      decision: { video, audio, audioTracks: [{ index: 1, codec: 'aac', channels: 2, language: 'eng' }], bitrate: 8_200_000, mode: 'remux', subtitles: [{ id: 's1', language: 'spa', source: 'embedded' }], auto: { step: '720', reason: 'measured 3.1 Mbps', generation: 0 } },
      source: { bitRate: 24_600_000 } as never,
      subtitleId: 's1',
      bufferedSeconds: 41.6,
    })
    expect(rows).toEqual([
      { label: 'Video', value: '1080p H.264 · as it is' },
      { label: 'Audio', value: 'English Stereo, AAC · as it is' },
      { label: 'Subtitles', value: 'Spanish' },
      { label: 'Bitrate', value: '8.2 Mb/s now · the file is 25 Mb/s' },
      { label: 'Buffer', value: '42 s ahead' },
      { label: 'Automatic quality', value: '720p · measured 3.1 Mbps' },
    ])
    expect(mbps(0)).toBe('')
    expect(autoWords({ step: 'original', reason: 'on the local network', generation: 0 })).toBe('Original quality · on the local network')
    const same = playbackRows({ decision: { video, audio, audioTracks: [], bitrate: 1_400_000, mode: 'audio', subtitles: [] }, source: { bitRate: 1_420_000 } as never, subtitleId: '', bufferedSeconds: 0 })
    expect(same.find((r) => r.label === 'Bitrate')?.value).toBe('1.4 Mb/s now')
    expect(same.find((r) => r.label === 'Buffer')?.value).toBe('Filling')
    expect(autoWords(undefined)).toBeUndefined()
  })
})

describe('tracks in words', () => {
  it('names sound tracks by language, then their kind', () => {
    expect(audioWords({ index: 0, codec: 'aac', channels: 6, language: 'eng' })).toEqual({ title: 'English', detail: '5.1 surround · AAC' })
    expect(audioWords({ index: 2, codec: 'aac', channels: 2, language: 'eng', title: 'Commentary' })).toEqual({ title: 'English', detail: 'Commentary · Stereo · AAC' })
    expect(audioWords({ index: 3, codec: 'dts', channels: 1 })).toEqual({ title: 'Track 4', detail: 'Mono · DTS' })
    expect(channelWords(8)).toBe('7.1 surround')
  })

  it('names subtitles by language, then how much they show', () => {
    expect(subtitleWords({ language: 'eng' })).toEqual({ title: 'English', detail: 'Full' })
    expect(subtitleWords({ language: 'eng', forced: true })).toEqual({ title: 'English', detail: 'Signs and foreign speech only' })
    expect(subtitleWords({ language: 'spa', hearingImpaired: true, external: true })).toEqual({ title: 'Spanish', detail: 'For the deaf and hard of hearing · Beside the file' })
    expect(subtitleWords({ language: 'eng', source: 'opensubtitles' }).detail).toBe('Full · Downloaded')
    expect(subtitleWords({ language: 'und', title: 'Signs', burnInOnly: true })).toEqual({ title: 'Signs', detail: 'Full · Drawn into the picture' })
    expect(onlineWords({ language: 'fr', hashMatch: true, release: 'Some.Release' })).toEqual({ title: 'French', detail: 'Matches this file · Some.Release' })
  })
})

describe('times and marks', () => {
  it('says what is left and when it ends', () => {
    const now = new Date(2026, 10, 23, 20, 12).getTime()
    expect(timeLeftLine(1390, 3132, now, 'en-US')).toBe('−29:02 · ends 8:41 PM')
    expect(timeLeftLine(10, 0, now)).toBe('')
  })

  it('marks the intro and credits on the bar', () => {
    const marks = segmentMarks(
      [
        { id: 1, kind: 'intro', startMs: 70_000, endMs: 125_000 },
        { id: 2, kind: 'credits', startMs: 2_960_000, endMs: 0 },
        { id: 3, kind: 'recap', startMs: 0, endMs: 500 },
        { id: 4, kind: 'unknown', startMs: 0, endMs: 10_000 },
      ],
      3132,
    )
    expect(marks.map((m) => [m.label, m.spoken])).toEqual([
      ['Intro', 'Intro, 1:10 to 2:05'],
      ['Credits', 'Credits, from 49:20'],
    ])
    expect(marks[0].left).toBeCloseTo(2.235, 2)
    expect(marks[1].left + marks[1].width).toBeCloseTo(100, 5)
    expect(segmentMarks(undefined, 100)).toEqual([])
  })
})

describe('volume', () => {
  it('reads back what was stored, or full volume', () => {
    expect(parseVolume('{"level":0.4,"muted":true}')).toEqual({ level: 0.4, muted: true })
    expect(parseVolume('{"level":7}')).toEqual({ level: 1, muted: false })
    expect(parseVolume('nonsense')).toEqual({ level: 1, muted: false })
    expect(parseVolume(null)).toEqual({ level: 1, muted: false })
  })

  it('steps a tenth, and louder unmutes', () => {
    expect(stepVolume({ level: 0.5, muted: false }, 0.1)).toEqual({ level: 0.6, muted: false })
    expect(stepVolume({ level: 0.05, muted: false }, -0.1)).toEqual({ level: 0, muted: true })
    expect(stepVolume({ level: 0.5, muted: true }, 0.1)).toEqual({ level: 0.6, muted: false })
    expect(stepVolume({ level: 0.5, muted: true }, -0.1)).toEqual({ level: 0.4, muted: true })
    expect(stepVolume({ level: 1, muted: false }, 0.1)).toEqual({ level: 1, muted: false })
    expect(volumeLabel({ level: 0.5, muted: true })).toBe('Unmute')
    expect(volumeLabel({ level: 0.5, muted: false })).toBe('Mute')
  })
})

describe('previous episode', () => {
  const ep = (id: number, number: number, hasFile = true) => ({ id, number, hasFile, files: hasFile ? [{ id: id * 10 }] : [] })
  it('finds the one before on disk in the season', () => {
    const eps = [ep(3, 3), ep(1, 1), ep(2, 2, false)]
    expect(previousInSeason(eps, 3)?.id).toBe(1)
    expect(previousInSeason(eps, 1)).toBeUndefined()
    expect(previousInSeason(eps, 99)).toBeUndefined()
  })

  it('goes back a season from the first episode', () => {
    expect(previousSeason([{ number: 0 }, { number: 1 }, { number: 2 }, { number: 3 }], 3)).toBe(2)
    expect(previousSeason([{ number: 0 }, { number: 1 }], 1)).toBeUndefined()
    expect(lastWithFile([ep(1, 1), ep(2, 2), ep(3, 3, false)])?.id).toBe(2)
    expect(episodeName(3, 6, 'Slack Water')).toBe('S3 E6, Slack Water')
  })
})

describe('a subtitle chosen before playing', () => {
  it('turns subtitles off, picks a track, or leaves it to the settings', () => {
    expect(chosenSubtitle('off')).toEqual({ subtitleMode: 'off' })
    expect(chosenSubtitle('s4')).toEqual({ subtitleMode: 'off', subtitleId: 's4' })
    expect(chosenSubtitle(undefined)).toBeUndefined()
  })
})
