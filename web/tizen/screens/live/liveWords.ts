import type { LiveChannel, LiveProgramme, LiveQuality } from '../../../src/api/liveTV'
import { duration, nowAndNext, progress, timeOfDay } from '../../../src/lib/liveGuide'
import { QUALITIES } from '../../../src/lib/quality'
import { channelNumberHint, exactChannel } from '../../player'

// The words Live TV's screens share, from the Samsung TV boards:
// what is on, how long is left, the qualities.

/** "7.1 LKSD-HD". */
export const channelLabel = (c: Pick<LiveChannel, 'number' | 'name'>) => `${c.number} ${c.name}`

/** "48 min left", "1 h 5 min left". */
export const leftWords = (end: number, now: number) => `${duration(end - now)} left`

const ms = (iso: string) => Date.parse(iso)

/** What is on a channel at `now`, from its now and next (so the card moves on at the end time without asking). */
export function onNow(channel: Pick<LiveChannel, 'now' | 'next'>, now: number): LiveProgramme | undefined {
  return nowAndNext(channel, now).now
}

export type OnNowCard = {
  channel: LiveChannel
  programme?: LiveProgramme
  /** The programme's title, or the channel's name without listings. */
  title: string
  /** "48 min left", or "No listings". */
  line: string
  /** 0–1 through the programme. */
  progress: number
  /** The card's accessible name: "Watch 2.2 Harbor Weather, Weather Now, 48 minutes left". */
  label: string
}

/** The On now row: every channel that can play, in the server's order, with what is on. */
export function onNowCards(channels: readonly LiveChannel[], now: number): OnNowCard[] {
  return channels.filter((c) => c.available && c.enabled).map((channel) => {
    const programme = onNow(channel, now)
    if (!programme) return { channel, title: channel.name, line: 'No listings', progress: 0, label: `Watch ${channelLabel(channel)}` }
    const minutes = Math.max(1, Math.ceil((ms(programme.end) - now) / 60_000))
    return {
      channel, programme, title: programme.title, line: leftWords(ms(programme.end), now), progress: progress(programme, now) / 100,
      label: `Watch ${channelLabel(channel)}, ${programme.title}, ${minutes} ${minutes === 1 ? 'minute' : 'minutes'} left`,
    }
  })
}

/** The line under the On now row: "Press OK to watch 2.2 Harbor Weather · Weather Now, 48 min left". */
export function onNowFocusLine(card: OnNowCard | undefined): string {
  if (!card) return ''
  const what = `Press OK to watch ${channelLabel(card.channel)}`
  return card.programme ? `${what} · ${card.title}, ${card.line}` : what
}

/** The Recordings tile's line: "1 recording now"; none while nothing records. */
export function recordingNowLine(count: number): string | undefined {
  if (!count) return undefined
  return count === 1 ? '1 recording now' : `${count} recordings now`
}

/** The clock in the corner: "8:12 PM". */
export const clockWords = (now: number) => timeOfDay(now)

// ---------------------------------------------------------------- quality

export type QualityOption = { id: LiveQuality; label: string; detail?: string }

/** Automatic's step in words: "720p", "Original", "Starting". */
const stepWords = (step?: string) => step ?? 'Starting'

/** The Quality panel's choices, in the engine's order, as the boards word them ("1080p · 8 Mbps"). */
export function qualityOptions(qualities: readonly LiveQuality[], autoStep?: string): QualityOption[] {
  return qualities.map((id) => {
    if (id === 'auto') return { id, label: 'Automatic', detail: autoStep ? `Now ${stepWords(autoStep)} · follows your connection` : 'Follows your connection' }
    const label = QUALITIES.find((q) => q.id === id)?.label ?? `${id}p`
    return { id, label, detail: id === '1080' ? 'Live TV default' : undefined }
  })
}

/** The Quality button's short value: "Auto · 720p", "1080p". */
export function qualityShort(quality: LiveQuality, autoStep?: string): string {
  return quality === 'auto' ? `Auto · ${stepWords(autoStep)}` : `${quality}p`
}

const MODES: Record<string, string> = { original: 'Direct', remux: 'Direct stream', audio: 'Audio transcode', video: 'Transcoding' }

/** The pill at the top right: "Starting", "Auto · 720p", "Transcoding · 1080p". */
export function streamLine(o: { tuning: boolean; quality: LiveQuality; autoStep?: string; mode?: string }): string {
  if (o.tuning) return 'Starting'
  if (o.quality === 'auto') return qualityShort('auto', o.autoStep)
  return [o.mode ? MODES[o.mode] : undefined, `${o.quality}p`].filter(Boolean).join(' · ')
}

/** "0:42 behind": how far behind the broadcast, at most an hour. */
export function behindWords(seconds: number): string {
  const s = Math.max(0, Math.round(seconds))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')} behind`
}

/** What a typed channel number names: the channel and what is on, or the engine's hint ("Keep entering the full channel number."). */
export function matchWords(channels: readonly LiveChannel[], digits: string, now: number): { text: string; match?: LiveChannel } {
  const match = exactChannel(channels, digits)
  if (!match) return { text: channelNumberHint(channels, digits) }
  const on = onNow(match, now)
  return { text: `${channelLabel(match)}${on ? ` · ${on.title}` : ''}`, match }
}
