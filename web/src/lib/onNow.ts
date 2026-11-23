import type { components } from '../api/schema'
import { duration, episodeLine, nowAndNext, panelPicture, progress, timeOfDay } from './liveGuide'

type LiveChannel = components['schemas']['LiveTVChannel']

/** A card in Home's On now row. */
export type OnNowCard = {
  channelId: string
  /** "4.1 NFLD". */
  channel: string
  title: string
  /** "Until 9:00 PM · 48 min left", or a movie's "Movie · 1958 · until 9:30 PM". */
  until: string
  /** "Next: Conference Football". */
  next?: string
  pct: number
  recording: boolean
  picture?: string
  logo?: string
  /** How the card reads aloud. */
  label: string
}

/**
 * The channels with something on now, favourites first, then by number, as
 * On now's cards. Channels that are off, unavailable or have no listing now
 * are left to the guide.
 */
export function onNowCards(channels: readonly LiveChannel[], now: number, locale?: string): OnNowCard[] {
  const cards: { card: OnNowCard; favourite: boolean; number: string }[] = []
  for (const ch of channels) {
    if (ch.enabled === false || ch.available === false) continue
    const { now: on, next } = nowAndNext(ch, now)
    if (!on) continue
    const end = Date.parse(on.end)
    const left = duration(end - now)
    const until = `until ${timeOfDay(end, locale)}`
    const movie = on.kind === 'movie' ? episodeLine(on) : ''
    const channel = [ch.number, ch.name].filter(Boolean).join(' ')
    const recording = on.recording?.state === 'recording'
    cards.push({ favourite: !!ch.favourite, number: ch.number, card: {
      channelId: ch.id,
      channel,
      title: on.title,
      until: movie ? `${movie} · ${until}` : `Until ${timeOfDay(end, locale)} · ${left} left`,
      next: next ? `Next: ${next.title}` : undefined,
      pct: Math.round(progress(on, now)),
      recording,
      picture: panelPicture(on)?.url,
      logo: ch.logoUrl,
      label: [`${on.title} on ${channel}`, `until ${timeOfDay(end, locale)}`, `${left} left`, recording ? 'recording' : '', next ? `next, ${next.title}` : ''].filter(Boolean).join(', '),
    } })
  }
  return cards
    .sort((a, b) => Number(b.favourite) - Number(a.favourite) || a.number.localeCompare(b.number, undefined, { numeric: true }))
    .map((c) => c.card)
}
