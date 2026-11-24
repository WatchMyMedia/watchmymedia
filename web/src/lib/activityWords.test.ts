import { describe, expect, it } from 'vitest'
import {
  conversionReasons,
  peakConvertedWords,
  bandwidthLine,
  byDay,
  clockWords,
  dayHeading,
  deviceWords,
  historyResult,
  kindsLine,
  liveDecision,
  liveKind,
  nowSentence,
  perDayWords,
  playDecision,
  playKind,
  playShares,
  rateWords,
  reasonWords,
  watchedWords,
} from './activityWords'

const play = (id: number, over: Record<string, unknown> = {}) =>
  ({ user: { id, name: `U${id}` }, state: 'playing', stream: { mode: 'direct', reasons: [] }, stalls: 0, ...over }) as never

describe('Now playing in words', () => {
  it('says how many are watching and whether the server keeps up', () => {
    expect(nowSentence({ plays: [], live: [] })).toEqual({ headline: 'Nobody is watching right now.', warn: false })
    expect(nowSentence({ plays: [play(1)], live: [] }).headline).toBe('1 person is watching. The server is keeping up.')
    expect(nowSentence({ plays: [play(1), play(1)], live: [{ userId: 2, userName: 'Sam' }, { userId: 3, userName: 'Alex' }] }).headline).toBe('3 people are watching. The server is keeping up.')
  })

  it('says when the server is struggling', () => {
    expect(nowSentence({ plays: [play(1)], live: [], transcoder: { sessions: 2, max: 2 } })).toEqual({ headline: '1 person is watching. Every converter slot is in use.', warn: true })
    expect(nowSentence({ plays: [play(1, { stream: { mode: 'transcode', reasons: [] }, transcode: { speed: 0.7, positionMs: 0 } })], live: [] }).headline).toMatch(/falling behind/)
    expect(nowSentence({ plays: [play(1, { state: 'buffering' })], live: [] }).headline).toMatch(/waiting for the picture/)
  })

  it('counts how plays are sent, and the bandwidth home and away', () => {
    expect(kindsLine(['original', 'repackaged', 'video'])).toBe('2 playing the original · 1 being converted')
    expect(kindsLine(['sound'])).toBe('1 with the sound converted')
    expect(kindsLine([])).toBe('')
    expect(bandwidthLine({ local: 22_000_000, tailnet: 200_000, remote: 4_000_000 })).toBe('Home 22 Mb/s · Away 4.2 Mb/s')
    expect(bandwidthLine({ local: 0, tailnet: 0, remote: 0 })).toBe('')
    expect(rateWords(850_000)).toBe('850 kb/s')
    expect(rateWords(4_000_000)).toBe('4 Mb/s')
  })

  it('names each mode as a kind', () => {
    expect([playKind('direct'), playKind('remux'), playKind('audio'), playKind('transcode')]).toEqual(['original', 'repackaged', 'sound', 'video'])
    expect([liveKind(undefined), liveKind('original'), liveKind('transcode')]).toEqual(['original', 'original', 'video'])
  })
})

describe('decisions in words', () => {
  it('words the server’s reasons', () => {
    expect(reasonWords('client cannot decode hevc video')).toBe('this device can’t play HEVC video')
    expect(reasonWords('client cannot decode hevc10 video')).toBe('this device can’t play 10-bit HEVC video')
    expect(reasonWords('client cannot decode eac3 audio', 'the TV')).toBe('the TV can’t play E-AC-3 sound')
    expect(reasonWords('3840x2160 exceeds the client\'s 1920x1080')).toBe('the picture is bigger than this device shows')
    expect(reasonWords('subtitle burn-in requested')).toBe('the subtitles are drawn into the picture')
    expect(reasonWords('something new')).toBe('something new')
  })

  it('says why a play is sent as it is', () => {
    expect(playDecision({ mode: 'direct', reasons: [], device: 'Living room TV' })).toEqual({ chip: 'Original quality', warn: false, why: 'Living room TV plays this file as it is.' })
    expect(playDecision({ mode: 'remux', reasons: [] }).why).toBe('Repackaged for this device; the picture and sound are untouched.')
    expect(playDecision({ mode: 'audio', reasons: ['client cannot decode dts audio'] }).why).toBe('Converting the sound: this device can’t play DTS sound.')
    expect(playDecision({ mode: 'transcode', reasons: [], step: '720', network: 'remote' })).toEqual({ chip: 'Converting video', warn: true, why: 'Away from home, so the picture is sent smaller (720p).' })
    expect(playDecision({ mode: 'transcode', reasons: ['client cannot decode hevc video'], step: 'original' }).why).toBe('Converting the video: this device can’t play HEVC video.')
    expect(playDecision({ mode: 'transcode', reasons: ["1920x1080 exceeds the client's 0x720"], step: '720', quality: '720', network: 'local' }).why).toBe('Sent smaller (720p), as chosen in the player.')
    expect(playDecision({ mode: 'transcode', reasons: [], step: '480', quality: 'auto', network: 'local' }).why).toBe('Sent smaller (480p) to keep playing smoothly.')
  })

  it('says how a channel is sent', () => {
    expect(liveDecision({ mode: 'original', automatic: false, quality: '1080' }, false).why).toBe('The broadcast is passed on as it arrives.')
    expect(liveDecision({ mode: 'transcode', automatic: true, actualQuality: '720', quality: 'auto' }, true).why).toBe('Away from home, so the picture is sent smaller (720p).')
    expect(liveDecision({ mode: 'video', modeReason: 'deinterlace_required', automatic: false, quality: '1080' }, false).why).toBe('Converting the video: the channel sends an interlaced picture, which players can’t show smoothly.')
    expect(liveDecision({ mode: 'video', modeReason: 'resolution_limit', automatic: false, quality: '720', actualQuality: '720' }, false).why).toBe('Sent smaller (720p), as chosen in the player.')
    expect(liveDecision({ mode: 'video', modeReason: 'adaptive_family', automatic: true, quality: 'auto', actualQuality: '480' }, false).why).toBe('Sent at 480p so it can change quality with the connection.')
    expect(liveDecision({ mode: 'video', modeReason: 'something_new', automatic: false, quality: '1080' }, false).why).toBe('Converting the video so this device can play it.')
  })
})

describe('History in words', () => {
  it('words lengths and positions', () => {
    expect(clockWords(3_730_000)).toBe('1:02:10')
    expect(clockWords(1_264_000)).toBe('21:04')
    expect(watchedWords(48 * 60_000)).toBe('48 min')
    expect(watchedWords(118 * 60_000)).toBe('1 h 58 min')
    expect(watchedWords((3 * 24 + 4) * 3_600_000)).toBe('3 days 4 hours')
    expect(watchedWords(0)).toBe('0 min')
  })

  it('says what came of a viewing', () => {
    expect(historyResult({ completed: true, percent: 100, positionMs: 0, playedMs: 48 * 60_000, durationMs: 48 * 60_000, groupCount: 2 })).toBe('Watched 48 min · 2 sittings')
    expect(historyResult({ completed: true, percent: 100, positionMs: 0, playedMs: 40 * 60_000, durationMs: 48 * 60_000, groupCount: 1 })).toBe('Watched 40 of 48 min')
    expect(historyResult({ completed: false, percent: 55, positionMs: 3_730_000, playedMs: 0, durationMs: 0, groupCount: 1 })).toBe('Stopped at 1:02:10 · 55%')
    expect(historyResult({ completed: false, percent: 55, positionMs: 3_730_000, playedMs: 0, durationMs: 0, groupCount: 3 }, false)).toBe('Stopped at 1:02:10 · 55%')
  })

  it('heads each day and groups rows by it', () => {
    const now = new Date(2026, 10, 24, 20, 0)
    expect(dayHeading(new Date(2026, 10, 24, 9), now, 'en-US')).toEqual({ label: 'Today', sub: 'Tuesday, Nov 24' })
    expect(dayHeading(new Date(2026, 10, 23, 23), now, 'en-US')).toEqual({ label: 'Yesterday', sub: 'Monday, Nov 23' })
    expect(dayHeading(new Date(2026, 10, 22, 9), now, 'en-US')).toEqual({ label: 'Sunday', sub: 'Nov 22' })
    expect(dayHeading(new Date(2026, 10, 2, 9), now, 'en-US')).toEqual({ label: 'Nov 2', sub: 'Monday' })
    expect(dayHeading(new Date(2025, 10, 2, 9), now, 'en-US').label).toBe('Nov 2, 2025')
    const rows = [new Date(2026, 10, 24, 18), new Date(2026, 10, 24, 9), new Date(2026, 10, 23, 21)].map((d) => ({ startedAt: d.toISOString() }))
    expect(byDay(rows, now, 'en-US').map((d) => [d.label, d.rows.length])).toEqual([['Today', 2], ['Yesterday', 1]])
  })

  it('says what a device is', () => {
    expect(deviceWords({ platform: 'android', product: 'Android TV 14', version: '2.1.0' })).toBe('Android TV 14 · app 2.1.0')
    expect(deviceWords({ platform: 'web', product: '', version: '' })).toBe('web')
    expect(deviceWords(undefined)).toBe('')
  })
})

describe('Stats in words', () => {
  it('splits the period by how it played, in shares that add up to 100', () => {
    const shares = playShares([
      { key: 'direct', values: [50, 10] },
      { key: 'remux', values: [11, 0] },
      { key: 'audio', values: [9, 0] },
      { key: 'transcode', values: [20, 0] },
    ])
    expect(shares.map((s) => [s.key, s.share])).toEqual([['original', 71], ['sound', 9], ['video', 20]])
    expect(playShares([{ key: 'direct', values: [1] }, { key: 'transcode', values: [2] }]).reduce((n, s) => n + s.share, 0)).toBe(100)
    expect(playShares([])).toEqual([])
  })

  it('says how often', () => {
    expect(perDayWords(214, 30)).toBe('About 7 a day')
    expect(perDayWords(4, 30)).toBe('About 1 a week')
    expect(perDayWords(1, 90)).toBe('Fewer than one a week')
    expect(perDayWords(0, 30)).toBe('')
  })
})

describe('Downloads in words', () => {
  it('words a copy and a transfer', async () => {
    const { copyWords, transferWhy } = await import('./activityWords')
    expect(copyWords('720', 'transcode')).toBe('Smaller · 720p')
    expect(copyWords('original', 'direct')).toBe('Original')
    expect(transferWhy({ mode: 'transcode', quality: '720', deviceName: 'Pixel 9' })).toBe('Making a smaller copy for Pixel 9 (720p).')
    expect(transferWhy({ mode: 'direct', quality: 'original', deviceName: 'Pixel 9' })).toBe('Sending the file as it is.')
  })

  it('counts what is on devices, leaving out revoked downloads', async () => {
    const { downloadsInShort } = await import('./activityWords')
    const d = (user: number, device: string, size: number, state = 'ready') => ({ user: { id: user, name: '' }, deviceName: device, sizeBytes: size, state }) as never
    expect(downloadsInShort([d(1, 'Pixel', 10), d(1, 'Pixel', 5), d(2, 'iPad', 7), d(2, 'iPad', 9, 'revoked')])).toEqual({ titles: 3, bytes: 22, devices: 2, people: 2 })
  })
})

describe('why plays were converted', () => {
  it('words each reason with its share of converted plays, most first', () => {
    expect(
      conversionReasons({
        plays: 20,
        reasons: [
          { reason: 'video-format', plays: 6 },
          { reason: 'smaller', plays: 12 },
          { reason: 'subtitles', plays: 2 },
        ],
      }),
    ).toEqual([
      { key: 'smaller', label: 'Sent smaller, chosen or for the connection', share: 60 },
      { key: 'video-format', label: 'The device can’t play the video’s format', share: 30 },
      { key: 'subtitles', label: 'Subtitles drawn into the picture', share: 10 },
    ])
  })
  it('folds reasons it doesn’t know into another reason, last among equals', () => {
    expect(conversionReasons({ plays: 4, reasons: [{ reason: 'teleport', plays: 1 }, { reason: 'other', plays: 1 }, { reason: 'bitrate', plays: 2 }] })).toEqual([
      { key: 'bitrate', label: 'More data than the device takes', share: 50 },
      { key: 'other', label: 'Another reason', share: 50 },
    ])
  })
  it('is empty with nothing converted', () => {
    expect(conversionReasons({ plays: 0, reasons: [] })).toEqual([])
    expect(conversionReasons(undefined)).toEqual([])
  })
  it('says how many of the peak were converted', () => {
    expect(peakConvertedWords(1)).toBe('1 converted')
    expect(peakConvertedWords(0)).toBe('none converted')
    expect(peakConvertedWords(undefined)).toBe('')
  })
})
