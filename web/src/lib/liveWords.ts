import type { components } from '../api/schema'
import { filterLiveChannels } from './liveTV'
import { joinNames, reasonWords } from './liveRecordings'
import { timeOfDay } from './liveGuide'

// The live pages' words and groupings: the guide's one search box, the
// player's "0:31 behind" and Playback info, and Recordings' shows, days,
// clashes and storage. The live rules themselves stay in liveGuide,
// liveRecordings and liveTV, which the Samsung app shares.

type Recording = components['schemas']['LiveTVRecording']
type RecordingRule = components['schemas']['LiveTVRecordingRule']
type LiveSession = components['schemas']['LiveTVSessionView']

const ms = (iso: string) => Date.parse(iso)

/** What the guide's one box does with what was typed: go to channels that match, or search the listings. */
export function guideBoxMode(text: string, channels: readonly { number: string; name: string }[], listingsSearch: boolean): 'none' | 'channels' | 'listings' {
  const t = text.trim()
  if (!t) return 'none'
  if (!listingsSearch || filterLiveChannels([...channels], t).length > 0) return 'channels'
  return 'listings'
}

export type GuideView = 'grid' | 'now'
/** A stored guide view, or undefined for anything else. */
export function parseGuideView(stored: string | null): GuideView | undefined {
  return stored === 'grid' || stored === 'now' ? stored : undefined
}

/** 8 PM on the day `dayStart` (a local midnight) begins. */
export function primeTime(dayStart: number): number {
  const d = new Date(dayStart)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 20, 0, 0, 0).getTime()
}

/** How far behind live, in words: "0:31 behind", "12:05 behind"; undefined within a few seconds of live. */
export function behindWords(seconds: number): string | undefined {
  if (!(seconds >= 5)) return undefined
  const s = Math.round(seconds)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')} behind`
}

/** A stored volume, 0 to 1; 1 when nothing usable is stored. */
export function parseVolume(stored: string | null): number {
  const v = stored === null ? NaN : Number(stored)
  return Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 1
}

/** What the server does to a live stream, in a few words, for Stream details. */
export function liveModeWords(mode?: string): string | undefined {
  switch (mode) {
    case 'original':
      return 'As broadcast'
    case 'remux':
      return 'Repackaged, not converted'
    case 'audio':
      return 'Converting the sound'
    case 'video':
      return 'Converting the video'
    default:
      return undefined
  }
}

/**
 * Playback info's sentence: what happens to the broadcast on its way to this
 * browser, and why, before the numbers.
 */
export function livePlaybackWords(s: Pick<LiveSession, 'mode' | 'modeReason' | 'automatic' | 'actualQuality' | 'quality'> & { height?: number }): string {
  const step = s.height ? `${s.height}p` : s.actualQuality && /^\d+$/.test(s.actualQuality) ? `${s.actualQuality}p` : undefined
  if (s.automatic && s.actualQuality && s.actualQuality !== 'original' && s.mode !== 'original' && s.mode !== 'remux')
    return `Converting the broadcast${step ? ` to ${step}` : ''} so it keeps up on this connection.`
  switch (s.mode) {
    case 'original':
      return 'Playing the broadcast as the channel sends it.'
    case 'remux':
      return 'Playing the broadcast’s picture and sound as sent, repackaged for this browser.'
    case 'audio':
      return 'Playing the picture as sent and converting the sound, which this browser can’t play.'
    case 'video':
      switch (s.modeReason) {
        case 'deinterlace_required':
          return 'Converting the video: the channel sends an interlaced picture, which browsers can’t show smoothly.'
        case 'resolution_limit':
          return `Converting the video${step ? ` to ${step}` : ''} to keep to the quality chosen here.`
        case 'bitrate_limit':
          return 'Converting the video so it fits the limit for this connection.'
        case 'video_unsupported':
          return 'Converting the video: this browser can’t play the channel’s video format.'
        case 'hdr_unsupported':
          return 'Converting the video: this browser can’t show the channel’s HDR.'
        case 'adaptive_family':
          return `Converting the broadcast${step ? ` to ${step}` : ''} so it can change quality with the connection.`
        default:
          return 'Converting the video so this browser can play it.'
      }
    default:
      return 'Playing the live broadcast.'
  }
}

/** A recording's length in minutes, from when it ran (or will run). */
export function recordingMinutes(r: Pick<Recording, 'start' | 'end' | 'startedAt' | 'endedAt'>): number {
  const a = ms(r.startedAt ?? r.start)
  const b = ms(r.endedAt ?? r.end)
  return Number.isFinite(a) && Number.isFinite(b) && b > a ? Math.round((b - a) / 60_000) : 0
}

/** A typical broadcast HD recording, about 19 Mb/s: used until this server's own recordings say otherwise. */
export const HD_BYTES_PER_HOUR = 8.5e9

/** "Room for about 70 hours", from the free space and the size of recordings so far. */
export function roomWords(freeBytes: number | undefined, recorded: readonly Pick<Recording, 'bytes' | 'start' | 'end' | 'startedAt' | 'endedAt'>[]): string | undefined {
  if (freeBytes === undefined) return undefined
  let bytes = 0
  let minutes = 0
  for (const r of recorded) {
    const m = recordingMinutes(r)
    if (r.bytes > 0 && m >= 10) {
      bytes += r.bytes
      minutes += m
    }
  }
  const perHour = minutes >= 30 ? (bytes / minutes) * 60 : HD_BYTES_PER_HOUR
  const hours = freeBytes / perHour
  if (hours < 1) return 'Room for less than an hour'
  const rounded = hours < 10 ? Math.floor(hours) : hours < 100 ? Math.round(hours / 5) * 5 : Math.round(hours / 10) * 10
  return `Room for about ${rounded} ${rounded === 1 ? 'hour' : 'hours'}`
}

/** One show's recordings on the Recorded tab. */
export type RecordingGroup = {
  key: string
  title: string
  picture?: string
  items: Recording[]
  rule?: RecordingRule
  latest: number
}

/**
 * Recorded, grouped by show: a recording with an episode, or made by a
 * series rule, joins its show's group, newest first; the rest (films,
 * specials, one-off manual recordings) are one-offs. Groups come newest
 * first.
 */
export function groupRecordings(items: readonly Recording[], rules: readonly RecordingRule[]): { groups: RecordingGroup[]; oneOffs: Recording[] } {
  const byKey = new Map<string, RecordingGroup>()
  const oneOffs: Recording[] = []
  const ruleOf = (r: Recording) => (r.ruleId ? rules.find((rule) => rule.id === r.ruleId) : undefined)
  for (const r of items) {
    const rule = ruleOf(r)
    const episodic = r.season != null || r.episode != null || !!r.episodeTitle || r.series || rule?.kind === 'series'
    if (!episodic) {
      oneOffs.push(r)
      continue
    }
    const key = r.title.trim().toLowerCase()
    const g = byKey.get(key) ?? { key, title: r.title, items: [], latest: 0 }
    g.items.push(r)
    g.rule ??= rule
    byKey.set(key, g)
  }
  const newest = (a: Recording, b: Recording) => ms(b.start) - ms(a.start)
  const groups = [...byKey.values()].map((g) => {
    const sorted = [...g.items].sort(newest)
    return { ...g, items: sorted, latest: ms(sorted[0].start), picture: sorted.find((r) => r.pictureUrl)?.pictureUrl }
  })
  return { groups: groups.sort((a, b) => b.latest - a.latest), oneOffs: oneOffs.sort(newest) }
}

/** "S2 E7 · The Night Train", "The Night Train", or the date it aired for a daily show. */
export function recordingEpisodeWords(r: Pick<Recording, 'season' | 'episode' | 'episodeTitle' | 'start'>, locale?: string): string {
  const code = r.season != null && r.episode != null ? (r.season >= 1000 ? `${r.season} E${r.episode}` : `S${r.season} E${r.episode}`) : ''
  const words = [code, r.episodeTitle ?? ''].filter(Boolean).join(' · ')
  return words || new Intl.DateTimeFormat(locale, { weekday: 'long', month: 'short', day: 'numeric' }).format(ms(r.start))
}

const dayKey = (at: number) => {
  const d = new Date(at)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

/** "Tonight", "Today", "Yesterday", "Tomorrow, Wed, Nov 25", "Thu, Nov 26": a day relative to now. */
export function dayWords(at: number, now: number, locale?: string, evening = false): string {
  const diff = Math.round((dayKey(at) - dayKey(now)) / 86_400_000)
  const date = new Intl.DateTimeFormat(locale, { weekday: 'short', month: 'short', day: 'numeric' }).format(at)
  if (diff === 0) return evening && new Date(at).getHours() >= 17 ? 'Tonight' : 'Today'
  if (diff === 1) return `Tomorrow, ${date}`
  if (diff === -1) return 'Yesterday'
  return date
}

/** "Recorded Sun, Nov 22 · 58 min", "Recorded yesterday · 30 min". */
export function recordedWords(r: Pick<Recording, 'start' | 'end' | 'startedAt' | 'endedAt'>, now: number, locale?: string): string {
  const day = dayWords(ms(r.start), now, locale)
  const minutes = recordingMinutes(r)
  return [`Recorded ${day === 'Today' || day === 'Yesterday' ? day.toLowerCase() : day}`, minutes ? `${minutes} min` : ''].filter(Boolean).join(' · ')
}

/** The Scheduled tab: what will record (and what won't), by day, soonest first. */
export function scheduleDays(items: readonly Recording[], now: number, locale?: string): { key: number; label: string; items: Recording[] }[] {
  const days = new Map<number, Recording[]>()
  for (const r of [...items].sort((a, b) => ms(a.start) - ms(b.start))) {
    const k = dayKey(ms(r.start))
    days.set(k, [...(days.get(k) ?? []), r])
  }
  return [...days.entries()].map(([key, list]) => ({
    key,
    label: dayWords(key === dayKey(now) ? Math.max(now, ms(list[0].start)) : key, now, locale, list.every((r) => new Date(ms(r.start)).getHours() >= 17)),
    items: list,
  }))
}

/** A clash as an alert: "Coastline Rescue won’t record tonight at 9:00 PM." and what holds the tuners. */
export function clashAlert(r: Pick<Recording, 'title' | 'start' | 'reason' | 'conflictsWith'>, now: number, locale?: string): { heading: string; detail: string } {
  const start = ms(r.start)
  const day = dayWords(start, now, locale, true)
  const when = day === 'Tonight' || day === 'Today' ? day.toLowerCase() : day.startsWith('Tomorrow') ? 'tomorrow' : `on ${day}`
  const heading = start <= now ? `${r.title} isn’t recording.` : `${r.title} won’t record ${when} at ${timeOfDay(start, locale)}.`
  const names = [...new Set((r.conflictsWith ?? []).map((h) => h.title))]
  if (r.reason === 'no_tuners' || !names.length) return { heading, detail: reasonWords(r.reason ?? 'tuners_busy') ?? 'The tuners are busy then.' }
  const detail = names.length === 1 ? `The tuners will be recording ${names[0]}.` : `The tuners will be recording ${joinNames(names)}.`
  return { heading, detail }
}

/** A rule in words: "New episodes · any channel · keeps the latest 5 · set by Sam". */
export function ruleWords(rule: Pick<RecordingRule, 'kind' | 'newOnly' | 'anyChannel' | 'number' | 'keep' | 'userName'>, manual?: string, you?: string): string {
  const who = rule.userName && rule.userName === you ? 'you' : rule.userName
  const parts =
    rule.kind === 'series'
      ? [rule.newOnly ? 'New episodes' : 'Every episode', rule.anyChannel ? 'any channel' : rule.number, rule.keep ? `keeps the latest ${rule.keep}` : 'keeps them all']
      : [manual ?? 'Repeats', rule.number]
  return [...parts.filter(Boolean), who ? `set by ${who}` : ''].filter(Boolean).join(' · ')
}

// What each person has watched of a recording, what kind it is, and the
// storage it fills (API level 34).

type Airing = components['schemas']['LiveTVAiring']
type SectionCounts = components['schemas']['LiveTVSectionCounts']

/** How far the signed-in person is into a recording, 0 to 100; 0 when they haven't started or have finished. */
export function recordingPct(r: Pick<Recording, 'progress' | 'watched'>): number {
  const p = r.progress
  if (r.watched || !p || p.durationMs <= 0 || p.positionMs <= 0) return 0
  return Math.min(100, Math.max(1, Math.round((p.positionMs / p.durationMs) * 100)))
}

/** Where the person is: "Watched", "18 min left", or nothing. */
export function recordingLeftWords(r: Pick<Recording, 'progress' | 'watched'>): string {
  if (r.watched) return 'Watched'
  const p = r.progress
  if (!p || p.durationMs <= 0 || p.positionMs <= 0) return ''
  const left = Math.max(1, Math.round((p.durationMs - p.positionMs) / 60_000))
  return `${minutesWords(left)} left`
}

const minutesWords = (min: number) => {
  const h = Math.floor(min / 60)
  const m = min % 60
  return h ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`
}

/** A show's group line: "3 episodes · 2 unwatched", "1 episode · watched". */
export function groupLine(items: readonly Pick<Recording, 'watched'>[], hasWatchState: boolean): string {
  const n = items.length
  const count = `${n} ${n === 1 ? 'episode' : 'episodes'}`
  if (!hasWatchState) return count
  const unwatched = items.filter((r) => !r.watched).length
  return [count, unwatched === 0 ? (n === 1 ? 'watched' : 'all watched') : `${unwatched} unwatched`].join(' · ')
}

/**
 * Whether the recordings carry the person's watch state: servers from API
 * level 34, whose first page counts each section. watched is sent only when
 * true, so a recording can't say it on its own.
 */
export function hasWatchState(first: { counts?: unknown } | undefined): boolean {
  return !!first?.counts
}

/** The Recorded tab's chips. */
export type RecordedChip = '' | 'unwatched' | 'shows' | 'movies' | 'sports'

const isSports = (r: Pick<Recording, 'categories'>) => (r.categories ?? []).some((c) => /sport/i.test(c))

/** Whether a recording is in a chip: one not watched, a show's episode, a film, or sport. */
export function inRecordedChip(r: Pick<Recording, 'kind' | 'categories' | 'watched' | 'season' | 'episode' | 'episodeTitle' | 'series'>, chip: RecordedChip): boolean {
  switch (chip) {
    case 'unwatched':
      return !r.watched
    case 'movies':
      return r.kind === 'movie'
    case 'sports':
      return isSports(r)
    case 'shows':
      return r.kind === 'episode' || r.kind === 'show' || (r.kind === undefined && (r.season != null || r.episode != null || !!r.episodeTitle || !!r.series))
    default:
      return true
  }
}

/** The chips worth showing: All, Unwatched where the server says what was watched, and the kinds only when some recording is one. */
export function recordedChips(items: readonly Recording[], watchState: boolean): { value: RecordedChip; label: string; count?: number }[] {
  const out: { value: RecordedChip; label: string; count?: number }[] = [{ value: '', label: 'All' }]
  if (watchState) out.push({ value: 'unwatched', label: 'Unwatched', count: items.filter((r) => inRecordedChip(r, 'unwatched')).length })
  for (const [value, label] of [['shows', 'Shows'], ['movies', 'Movies'], ['sports', 'Sports']] as const) {
    if (items.some((r) => inRecordedChip(r, value))) out.push({ value, label })
  }
  return out
}

/** A film's or a one-off's line: "Movie · 1949 · 1 h 31 min", "Documentary · 52 min". */
export function oneOffWords(r: Pick<Recording, 'kind' | 'year' | 'categories' | 'start' | 'end' | 'startedAt' | 'endedAt' | 'episodeTitle'>): string {
  const minutes = recordingMinutes(r)
  const length = minutes ? minutesWords(minutes) : ''
  if (r.kind === 'movie') return ['Movie', r.year ? String(r.year) : '', length].filter(Boolean).join(' · ')
  const category = (r.categories ?? []).find((c) => !/^(series|movie|special)$/i.test(c))
  return [r.episodeTitle || category || '', length].filter(Boolean).join(' · ')
}

/** "Also on Thu 2:00 AM": the next airing of a repeat that won't record, so it can be recorded then. */
export function alsoOnWords(a: Airing | undefined, now: number, locale?: string): string {
  if (!a) return ''
  const at = Date.parse(a.start)
  if (!Number.isFinite(at)) return ''
  const day = dayWords(at, now, locale, true)
  const when = day === 'Tonight' || day === 'Today' ? day.toLowerCase() : day.startsWith('Tomorrow') ? 'tomorrow' : new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at)
  return `Also on ${when} ${timeOfDay(at, locale)} on ${a.number}`
}

/** The storage panel's figures: room left as hours, how full the disk is, and the recordings' share. */
export function storageFacts(
  first: { freeBytes?: number; totalBytes?: number; reserveBytes?: number; counts?: SectionCounts } | undefined,
): { usable?: number; usedPct?: number; recordings?: { items: number; bytes: number } } {
  if (!first) return {}
  const usable = first.freeBytes !== undefined ? Math.max(0, first.freeBytes - (first.reserveBytes ?? 0)) : undefined
  const total = first.totalBytes
  const usedPct = total && first.freeBytes !== undefined ? Math.min(100, Math.max(0, Math.round(((total - first.freeBytes) / total) * 100))) : undefined
  return { usable, usedPct, recordings: first.counts?.recorded }
}
