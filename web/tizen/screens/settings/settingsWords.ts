import type { components } from '../../../src/api/schema'
import { languageName, relativeTime } from '../../../src/lib/format'
import { subtitleChoices, type SubtitleChoice } from '../../../src/lib/languagePreferences'
import { AUTO } from '../../../src/lib/quality'
import { SKIP_KINDS } from '../../../src/lib/youSettings'
import type { SubtitleSize } from '../../player'

// Settings' words on the TV, as the Android TV app says them:
// each group's state line, the choices' labels and what each one does.

type MediaRequest = components['schemas']['MediaRequest']
type Session = components['schemas']['Session']

/** What a Default quality does, under it. */
export function qualityDescription(id: string): string {
  if (id === AUTO) return 'Original first, lower when it can’t keep up'
  if (id === 'original') return 'Direct play when the TV can'
  return 'Transcode to fit'
}

export const SUBTITLE_SIZES: readonly { id: SubtitleSize; label: string }[] = [
  { id: 'smaller', label: 'Smaller' },
  { id: 'normal', label: 'Normal' },
  { id: 'larger', label: 'Larger' },
  { id: 'largest', label: 'Largest' },
]

export const subtitleSizeLabel = (size: SubtitleSize) => SUBTITLE_SIZES.find((s) => s.id === size)?.label ?? 'Normal'

/** The five skip rows, one a part, in the web's order, with the TV's names for them. */
export const SKIP_ROWS: readonly { kind: string; label: string; note?: string; part: string }[] = SKIP_KINDS.map((k) => ({
  kind: k.kind,
  label: k.kind === 'preview' ? 'Skip previews' : `Skip ${k.label.toLowerCase()}`,
  note: k.kind === 'credits' ? 'Hidden when the Next Episode card shows' : undefined,
  part: ({ intro: 'The intro plays.', recap: 'The recap plays.', credits: 'The credits play.', preview: 'The previews play.', commercial: 'The ads play.' } as Record<string, string>)[k.kind] ?? 'It plays.',
}))

/** A skip row's four modes, as the server names them, in the panel's order. */
export const TV_SKIP_MODES: readonly { value: string; label: string }[] = [
  { value: 'none', label: 'Never' },
  { value: 'ask', label: 'Ask' },
  { value: 'auto', label: 'Auto' },
  { value: 'auto-delay', label: 'Auto after 3 s' },
]

export const skipLabel = (mode: string) => TV_SKIP_MODES.find((m) => m.value === mode)?.label ?? 'Ask'

/** What a skip mode does to a part, under it in the panel: "No button. The intro plays." */
export function skipExplanation(mode: string, kind: string): string {
  if (mode === 'none') return `No button. ${SKIP_ROWS.find((r) => r.kind === kind)?.part ?? 'It plays.'}`
  if (mode === 'ask') return 'Shows a Skip button.'
  if (mode === 'auto') return 'Skips after one second.'
  return 'Shows the button filling up, then skips.'
}

/** The Live TV reminder lead times, with the TV's labels. */
export const LEADS: readonly number[] = [0, 1, 5, 10, 15]
export const leadLabel = (minutes: number) => minutes === 0 ? 'When it starts' : `${minutes} min before`

/** Playback's state line: "Automatic · autoplay on". */
export function playbackLine(quality: string, autoplayNext: boolean | undefined): string {
  return autoplayNext === undefined ? quality : `${quality} · autoplay ${autoplayNext ? 'on' : 'off'}`
}

export const choiceOf = (id: SubtitleChoice) => subtitleChoices.find((c) => c.id === id) ?? subtitleChoices[1]

/** Audio & subtitles' state line: "Spanish, English · for foreign audio"; the file's default with no audio languages. */
export function languagesLine(audio: readonly string[], choice: SubtitleChoice): string {
  const names = audio.length ? audio.slice(0, 2).map((t) => languageName(t)).join(', ') + (audio.length > 2 ? ', …' : '') : 'File default'
  return `${names} · ${choiceOf(choice).short.toLowerCase()}`
}

/** What When a video starts says under it, naming the person's own audio languages for "For foreign audio". */
export function startSubtitlesLine(choice: SubtitleChoice, audio: readonly string[]): string {
  if (choice !== 'foreign' || !audio.length) return choiceOf(choice).line
  const names = audio.map((t) => languageName(t))
  const list = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} or ${names[names.length - 1]}`
  return `Full subtitles when the audio isn’t ${list}`
}

/** Home rows' state line: "8 rows · 2 hidden". */
export function rowsLine(shown: number, hidden: number): string {
  return `${shown === 1 ? '1 row' : `${shown} rows`}${hidden ? ` · ${hidden} hidden` : ''}`
}

/** Notifications' state line: "3 shows followed". */
export function followsLine(count: number): string {
  if (count === 0) return 'None'
  return `${count === 1 ? '1 show' : `${count} shows`} followed`
}

/** Account & devices' state line: "Sam · 4 devices signed in"; the name alone until the devices are known. */
export function accountLine(name: string, devices: number | undefined): string {
  if (devices === undefined) return name
  return `${name} · ${devices === 1 ? '1 device' : `${devices} devices`} signed in`
}

/** Your requests' state line, short: "2 waiting", "1 downloading", "3 requests", "None". */
export function requestsLine(requests: readonly Pick<MediaRequest, 'status' | 'download'>[]): string {
  if (!requests.length) return 'None'
  const waiting = requests.filter((r) => r.status === 'pending').length
  if (waiting) return `${waiting} waiting`
  const downloading = requests.filter((r) => r.download && r.status !== 'available').length
  if (downloading) return `${downloading} downloading`
  return requests.length === 1 ? '1 request' : `${requests.length} requests`
}

/** About's state line: "App 0.1.0 · Lakeside 1.8.0". */
export function aboutLine(appVersion: string, serverName: string, serverVersion: string): string {
  return `App ${appVersion} · ${serverName} ${serverVersion.replace(/^v/, '')}`
}

/** The Inbox's state line: "3 to look at". */
export function inboxLine(count: number | undefined): string {
  return count ? `${count} to look at` : 'Nothing to look at'
}

/** A signed-in device's line: "iPad · 2 h ago", "Samsung TV · now" for this one. */
export function deviceLine(s: Pick<Session, 'model' | 'product' | 'platform' | 'kind' | 'lastSeenAt' | 'current'>): string {
  const what = s.model || s.product || (s.kind === 'browser' ? 'Browser' : s.platform) || 'Device'
  return `${what} · ${s.current ? 'now' : relativeTime(s.lastSeenAt)}`
}

/** A choice that didn't save, on its row: what it is still, and how to try again with the remote. */
export function saveFailedLine(still: string): string {
  return `Couldn’t save, so it’s still ${still}. Press OK to try again.`
}

/** The web app's address on the server, for a QR: Server settings, or Change password. */
export function webAppUrl(baseUrl: string, path: '/settings' | '/settings/account'): string {
  return baseUrl.replace(/\/+$/, '') + path
}

/** watchmy.media's privacy policy, as Android and Apple link it. */
export const PRIVACY_URL = 'https://watchmy.media/privacy'

/** The libraries in this app, with their licences (About › Open-source licences). */
export const LICENCES: readonly { name: string; licence: string }[] = [
  { name: 'React and React DOM', licence: 'MIT License' },
  { name: 'TanStack Query', licence: 'MIT License' },
  { name: 'openapi-fetch', licence: 'MIT License' },
  { name: 'qrcode-generator', licence: 'MIT License' },
]
