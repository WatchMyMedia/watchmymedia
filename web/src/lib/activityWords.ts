import type { components } from '../api/schema'

// Activity in words: what is playing and how, what was watched, by day,
// and the numbers behind it, said the way a person would say them. The
// stream's codecs and rates stay one step away, in Stream details.

type LivePlay = components['schemas']['ActivityLivePlay']
type LiveSession = components['schemas']['LiveTVSessionView']
type HistoryRow = components['schemas']['ActivityHistoryRow']
type TranscoderLoad = components['schemas']['TranscoderLoad']
type Device = components['schemas']['ActivityDevice']

/** "22 Mb/s", "850 kb/s". */
export function rateWords(bps: number): string {
  if (bps <= 0) return '0 Mb/s'
  if (bps < 1_000_000) return `${Math.round(bps / 1000)} kb/s`
  const mb = bps / 1_000_000
  return `${mb < 10 ? mb.toFixed(1).replace(/\.0$/, '') : Math.round(mb)} Mb/s`
}

/** "1 person", "3 people". */
export function peopleWords(n: number): string {
  return `${n} ${n === 1 ? 'person' : 'people'}`
}

/** How a play is sent, as everyone's plays and Live TV's sessions both say it. */
export type SendKind = 'original' | 'repackaged' | 'sound' | 'video'

/** A library play's mode as a kind. */
export function playKind(mode: string): SendKind {
  switch (mode) {
    case 'remux':
      return 'repackaged'
    case 'audio':
      return 'sound'
    case 'transcode':
      return 'video'
    default:
      return 'original'
  }
}

/** A Live TV session's mode as a kind; a session still tuning counts as the original. */
export function liveKind(mode: string | undefined): SendKind {
  switch (mode) {
    case 'remux':
      return 'repackaged'
    case 'audio':
      return 'sound'
    case 'transcode':
    case 'video':
      return 'video'
    default:
      return 'original'
  }
}

/**
 * Now playing's first sentence: how many people are watching, and whether
 * the server keeps up: "3 people are watching. The server is keeping up."
 */
export function nowSentence(input: {
  plays: Pick<LivePlay, 'user' | 'state' | 'stream' | 'transcode' | 'stalls'>[]
  live: Pick<LiveSession, 'userId' | 'userName'>[]
  transcoder?: Pick<TranscoderLoad, 'sessions' | 'max'>
}): { headline: string; warn: boolean } {
  const people = new Set([...input.plays.map((p) => `u${p.user.id}`), ...input.live.map((s) => `u${s.userId ?? s.userName}`)]).size
  if (people === 0) return { headline: 'Nobody is watching right now.', warn: false }
  const who = people === 1 ? '1 person is watching.' : `${people} people are watching.`
  const t = input.transcoder
  if (t && t.max > 0 && t.sessions >= t.max) return { headline: `${who} Every converter slot is in use.`, warn: true }
  if (input.plays.some((p) => p.stream.mode === 'transcode' && p.transcode && p.transcode.speed > 0 && p.transcode.speed < 1))
    return { headline: `${who} A conversion is falling behind.`, warn: true }
  if (input.plays.some((p) => p.state === 'buffering')) return { headline: `${who} Someone is waiting for the picture.`, warn: true }
  return { headline: `${who} The server is keeping up.`, warn: false }
}

/** "2 playing the original · 1 being converted": how the plays now are sent. */
export function kindsLine(kinds: SendKind[]): string {
  const n = (k: SendKind[]) => kinds.filter((x) => k.includes(x)).length
  const original = n(['original', 'repackaged'])
  const sound = n(['sound'])
  const video = n(['video'])
  return [original ? `${original} playing the original` : '', sound ? `${sound} with the sound converted` : '', video ? `${video} being converted` : ''].filter(Boolean).join(' · ')
}

/** "Home 22 Mb/s · Away 4.2 Mb/s", leaving out a side with nothing on it. */
export function bandwidthLine(bw: { local: number; tailnet: number; remote: number }): string {
  const away = bw.tailnet + bw.remote
  return [bw.local > 0 ? `Home ${rateWords(bw.local)}` : '', away > 0 ? `Away ${rateWords(away)}` : ''].filter(Boolean).join(' · ')
}

/** A play's decision: the chip, whether it costs the server, and why, in a sentence. */
export type Decision = { chip: string; warn: boolean; why: string }

const CODEC: Record<string, string> = { hevc: 'HEVC', hevc10: '10-bit HEVC', h265: 'HEVC', h264: 'H.264', avc: 'H.264', av1: 'AV1', vp9: 'VP9', mpeg2video: 'MPEG-2', eac3: 'E-AC-3', ac3: 'AC-3', dts: 'DTS', truehd: 'TrueHD', aac: 'AAC', flac: 'FLAC', opus: 'Opus' }

/** One of the server's reasons in plain words, or the reason as it is. */
export function reasonWords(reason: string, device = 'this device'): string {
  let m = /client cannot decode (\S+) video/.exec(reason)
  if (m) return `${device} can’t play ${CODEC[m[1].toLowerCase()] ?? m[1].toUpperCase()} video`
  m = /client cannot decode (\S+) audio/.exec(reason)
  if (m) return `${device} can’t play ${CODEC[m[1].toLowerCase()] ?? m[1].toUpperCase()} sound`
  if (/exceeds the client's \d+x\d+/.test(reason)) return `the picture is bigger than ${device} shows`
  if (/^bitrate .* exceeds/.test(reason)) return `the file needs more than ${device} takes`
  if (/audio channels exceed/.test(reason)) return `${device} takes fewer sound channels`
  if (/subtitle burn-in/.test(reason)) return 'the subtitles are drawn into the picture'
  m = /client cannot play the (\S+) container/.exec(reason)
  if (m) return `${device} can’t open ${m[1].toUpperCase()} files`
  if (/selected audio track requires a remux/.test(reason)) return 'the sound chosen isn’t the file’s first'
  if (/Dolby Vision/.test(reason)) return 'Dolby Vision is converted, so colours may be off'
  return reason
}

/**
 * A library play's decision in words: "Original quality" and "The TV plays
 * this file as it is.", or "Converting video" and "Away from home, so the
 * picture is sent smaller (720p)."
 */
export function playDecision(p: { mode: string; reasons: string[]; step?: string; quality?: string; network?: string; device?: string }): Decision {
  const device = p.device || 'This device'
  const reason = p.reasons[0] ? reasonWords(p.reasons[0], 'this device') : ''
  const smaller = !!p.step && p.step !== 'original'
  // A size over the chosen step is the step itself, not the device.
  const sizeOnly = p.reasons.length > 0 && p.reasons.every((r) => /exceeds the client's \d+x\d+|^bitrate .* exceeds/.test(r))
  switch (playKind(p.mode)) {
    case 'original':
      return { chip: 'Original quality', warn: false, why: `${device} plays this file as it is.` }
    case 'repackaged':
      return { chip: 'Original quality', warn: false, why: `Repackaged for ${lower(device)}; the picture and sound are untouched.` }
    case 'sound':
      return { chip: 'Converting sound', warn: false, why: reason ? `Converting the sound: ${reason}.` : 'Converting the sound; the picture is untouched.' }
    case 'video':
      if (smaller && p.network && p.network !== 'local') return { chip: 'Converting video', warn: true, why: `Away from home, so the picture is sent smaller (${p.step}p).` }
      if (smaller && p.quality === 'auto' && (!reason || sizeOnly)) return { chip: 'Converting video', warn: true, why: `Sent smaller (${p.step}p) to keep playing smoothly.` }
      if (smaller && (!reason || sizeOnly)) return { chip: 'Converting video', warn: true, why: `Sent smaller (${p.step}p), as chosen in the player.` }
      return { chip: 'Converting video', warn: true, why: reason ? `Converting the video: ${reason}.` : 'Converting the video.' }
  }
}

function lower(device: string): string {
  return device === 'This device' ? 'this device' : device
}

/** Why the server converts a channel, from its reason code, as the end of "Converting the video: …". */
const LIVE_REASONS: Record<string, string> = {
  deinterlace_required: 'the channel sends an interlaced picture, which players can’t show smoothly',
  bitrate_limit: 'it has to fit the limit for this connection',
  video_unsupported: 'this device can’t play the channel’s video format',
  hdr_unsupported: 'this device can’t show the channel’s HDR',
  segment_format_unsupported: 'this device can’t open the broadcast as it is packaged',
  source_unknown: 'the channel’s format wasn’t known when it started',
  legacy_client: 'the app on this device asks for a converted picture',
}

/** A Live TV session's decision in words. */
export function liveDecision(s: Pick<LiveSession, 'mode' | 'modeReason' | 'automatic' | 'actualQuality' | 'quality'>, away: boolean): Decision {
  const kind = liveKind(s.mode)
  if (kind === 'original' || kind === 'repackaged') return { chip: 'Original quality', warn: false, why: 'The broadcast is passed on as it arrives.' }
  if (kind === 'sound') return { chip: 'Converting sound', warn: false, why: 'Converting the sound; the picture is passed on as it arrives.' }
  const step = s.actualQuality && /^\d+$/.test(s.actualQuality) ? s.actualQuality : /^\d+$/.test(s.quality) ? s.quality : ''
  if (away && step) return { chip: 'Converting video', warn: true, why: `Away from home, so the picture is sent smaller (${step}p).` }
  if (s.modeReason === 'resolution_limit') return { chip: 'Converting video', warn: true, why: `Sent smaller${step ? ` (${step}p)` : ''}, as chosen in the player.` }
  if (s.modeReason === 'adaptive_family' || (s.automatic && step)) return { chip: 'Converting video', warn: true, why: `Sent${step ? ` at ${step}p` : ''} so it can change quality with the connection.` }
  const why = s.modeReason ? LIVE_REASONS[s.modeReason] : undefined
  return { chip: 'Converting video', warn: true, why: why ? `Converting the video: ${why}.` : 'Converting the video so this device can play it.' }
}

/** "1:02:10" or "21:04", from milliseconds. */
export function clockWords(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** "48 min", "1 h 58 min", "3 days 4 hours": a length watched. */
export function watchedWords(ms: number): string {
  const min = Math.round(ms / 60_000)
  if (min < 1) return ms > 0 ? 'under a minute' : '0 min'
  const days = Math.floor(min / 1440)
  if (days > 0) {
    const hours = Math.floor((min % 1440) / 60)
    return `${days} ${days === 1 ? 'day' : 'days'}${hours ? ` ${hours} ${hours === 1 ? 'hour' : 'hours'}` : ''}`
  }
  const h = Math.floor(min / 60)
  const m = min % 60
  return h ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`
}

/** A history row's result: "Watched 48 of 48 min · 2 sittings", "Stopped at 1:02:10 · 55%". */
export function historyResult(r: Pick<HistoryRow, 'completed' | 'percent' | 'positionMs' | 'playedMs' | 'durationMs' | 'groupCount'>, grouped = true): string {
  const sittings = grouped && r.groupCount > 1 ? ` · ${r.groupCount} sittings` : ''
  if (r.completed) {
    const total = Math.round(r.durationMs / 60_000)
    const played = Math.min(total, Math.round(r.playedMs / 60_000))
    return total > 0 ? `Watched ${played === total ? total : `${played} of ${total}`} min${sittings}` : `Watched to the end${sittings}`
  }
  return `Stopped at ${clockWords(r.positionMs)} · ${r.percent}%${sittings}`
}

/** How a sitting ended. */
export const ENDED_WORDS: Record<string, string> = {
  stopped: 'Stopped',
  ended: 'Reached the end',
  closed: 'Closed the page',
  timeout: 'Stopped reporting',
  terminated: 'Stopped by an admin',
}

/** "Android TV 14 · app 2.1.0", from what a device says of itself. */
export function deviceWords(d?: Pick<Device, 'platform' | 'product' | 'version'>): string {
  if (!d) return ''
  return [d.product || d.platform, d.version ? `app ${d.version}` : ''].filter(Boolean).join(' · ')
}

/** "At home", "Away", "Away · Tailscale". */
export const NETWORK_WORDS: Record<string, string> = { local: 'At home', tailnet: 'Away · tailnet', remote: 'Away' }

/** A calendar day's heading in History: "Today", "Yesterday", "Sunday" this week, else "Nov 22"; and its date. */
export function dayHeading(at: Date, now: Date, locale?: string): { label: string; sub: string } {
  const day = new Date(at.getFullYear(), at.getMonth(), at.getDate())
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const ago = Math.round((today.getTime() - day.getTime()) / 86_400_000)
  const sameYear = day.getFullYear() === today.getFullYear()
  const date = day.toLocaleDateString(locale, sameYear ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' })
  const weekday = day.toLocaleDateString(locale, { weekday: 'long' })
  if (ago === 0) return { label: 'Today', sub: `${weekday}, ${date}` }
  if (ago === 1) return { label: 'Yesterday', sub: `${weekday}, ${date}` }
  if (ago > 1 && ago < 7) return { label: weekday, sub: date }
  return { label: date, sub: weekday }
}

/** Rows split by the local day they started, in the order given. */
export function byDay<T extends { startedAt: string }>(rows: readonly T[], now: Date, locale?: string): { key: string; label: string; sub: string; rows: T[] }[] {
  const out: { key: string; label: string; sub: string; rows: T[] }[] = []
  for (const r of rows) {
    const at = new Date(r.startedAt)
    const key = `${at.getFullYear()}-${at.getMonth()}-${at.getDate()}`
    const last = out.at(-1)
    if (last?.key === key) last.rows.push(r)
    else out.push({ key, ...dayHeading(at, now, locale), rows: [r] })
  }
  return out
}

/** "8:45 PM", a row's time of day. */
export function timeWords(iso: string, locale?: string): string {
  return new Date(iso).toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' }).replace(/\u202f/g, ' ')
}

/**
 * How it played, as shares of the period that add up to 100: the original,
 * the sound converted, the video converted, from the server's lines by mode.
 */
export function playShares(lines: { key: string; values: number[] }[]): { key: 'original' | 'sound' | 'video'; label: string; note: string; share: number }[] {
  const total = (keys: string[]) => lines.filter((l) => keys.includes(l.key)).reduce((n, l) => n + l.values.reduce((a, b) => a + b, 0), 0)
  const parts = [
    { key: 'original' as const, label: 'Original', note: 'the file as it is', v: total(['direct', 'remux']) },
    { key: 'sound' as const, label: 'Sound converted', note: 'the picture untouched', v: total(['audio']) },
    { key: 'video' as const, label: 'Video converted', note: 'a smaller or different picture', v: total(['transcode']) },
  ]
  const sum = parts.reduce((n, p) => n + p.v, 0)
  if (sum <= 0) return []
  // Largest remainder, so the shares shown add up to 100.
  const raw = parts.map((p) => (p.v / sum) * 100)
  const floor = raw.map(Math.floor)
  let left = 100 - floor.reduce((a, b) => a + b, 0)
  const order = raw.map((r, i) => [r - floor[i], i] as const).sort((a, b) => b[0] - a[0])
  for (const [, i] of order) {
    if (left <= 0) break
    floor[i]++
    left--
  }
  return parts.map((p, i) => ({ key: p.key, label: p.label, note: p.note, share: floor[i] })).filter((p) => p.share > 0)
}

/** "About 7 a day", "About 1 a week", or nothing for no plays. */
export function perDayWords(plays: number, days: number): string {
  if (plays <= 0 || days <= 0) return ''
  const a = plays / days
  if (a >= 1) return `About ${Math.round(a)} a day`
  const w = a * 7
  return Math.round(w) >= 1 ? `About ${Math.round(w)} a week` : 'Fewer than one a week'
}

type Download = components['schemas']['OfflineDownload']
type Transfer = components['schemas']['OfflineTransfer']

/** A download's copy: "Original", or "Smaller · 720p" for one the server made smaller. */
export function copyWords(quality: string, mode: string): string {
  if (mode !== 'transcode') return 'Original'
  return `Smaller · ${quality && quality !== 'original' ? `${quality}p` : 'converted'}`
}

/** The downloads in short: "14 titles · 38 GB" on "5 devices, for 4 people"; revoked ones left out. */
export function downloadsInShort(items: readonly Pick<Download, 'state' | 'sizeBytes' | 'deviceName' | 'user'>[]): { titles: number; bytes: number; devices: number; people: number } {
  const kept = items.filter((d) => d.state !== 'revoked')
  return {
    titles: kept.length,
    bytes: kept.reduce((n, d) => n + d.sizeBytes, 0),
    devices: new Set(kept.map((d) => `${d.user?.id ?? 0}/${d.deviceName ?? ''}`)).size,
    people: new Set(kept.map((d) => d.user?.id ?? 0)).size,
  }
}

/** Why a transfer is sent as it is: "Making a smaller copy for the phone (720p).", "Sending the file as it is." */
export function transferWhy(t: Pick<Transfer, 'mode' | 'quality' | 'deviceName'>): string {
  if (t.mode !== 'transcode') return 'Sending the file as it is.'
  const q = t.quality && t.quality !== 'original' ? ` (${t.quality}p)` : ''
  return `Making a smaller copy for ${t.deviceName || 'the device'}${q}.`
}
