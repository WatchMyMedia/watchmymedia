import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVChannelsQuery } from '../../api/queries'
import { onNowCards, type OnNowCard } from '../../lib/onNow'
import { useNow } from '../useNow'

/** How many channels Home's On now row shows; the guide has the rest. */
const SHOWN = 12

/**
 * Live TV on Home: what is on now, favourites first, each card saying until
 * when, how long is left and what follows, and opening the channel. Nothing
 * plays until a card is chosen.
 */
export function OnNowRow({ title = 'On now' }: { title?: string }) {
  const channels = useQuery(liveTVChannelsQuery)
  const now = useNow()
  const cards = onNowCards(channels.data?.items ?? [], now).slice(0, SHOWN)
  if (!cards.length) return null
  const today = new Intl.DateTimeFormat(undefined, { weekday: 'long', hour: 'numeric', minute: '2-digit' }).format(now).replace(/\u202f/g, ' ')
  return (
    <section aria-labelledby="h-on-now">
      <div className="rowh">
        <h2 id="h-on-now">{title}</h2>
        <span className="note">{today}</span>
        <Link to="/live-tv" className="more">
          Guide ›
        </Link>
      </div>
      <div className="scrollbar-hide -mx-1 mt-3.5 flex gap-3 overflow-x-auto px-1 pb-1.5 pt-1 md:gap-4">
        {cards.map((c) => (
          <OnNowCardView key={c.channelId} card={c} />
        ))}
      </div>
    </section>
  )
}

function OnNowCardView({ card: c }: { card: OnNowCard }) {
  const [broken, setBroken] = useState('')
  const picture = c.picture && c.picture !== broken ? c.picture : undefined
  const logo = c.logo && c.logo !== broken ? c.logo : undefined
  return (
    <Link to="/live-tv/watch/$channelId" params={{ channelId: c.channelId }} aria-label={c.label} className="card group block w-[264px] shrink-0 rounded-lg">
      <span className="relative block aspect-video overflow-hidden rounded-lg bg-surface-2 ring-1 ring-inset ring-white/[.07]">
        {picture ? (
          <img src={picture} alt="" loading="lazy" onError={() => setBroken(picture)} className="size-full object-cover" />
        ) : logo ? (
          <img src={logo} alt="" loading="lazy" onError={() => setBroken(logo)} className="absolute inset-0 m-auto max-h-[45%] max-w-[55%] object-contain" />
        ) : (
          <span className="absolute inset-0 grid place-items-center p-4 text-center text-sm font-semibold text-neutral-400">{c.title}</span>
        )}
        <span className="absolute left-2 top-2 rounded-[5px] bg-ground/80 px-[7px] py-0.5 text-[11px] font-bold text-neutral-100 ring-1 ring-inset ring-white/15">{c.channel}</span>
        {c.recording && <span className="absolute right-2 top-2 rounded-[5px] bg-brand px-[7px] py-0.5 text-[11px] font-bold text-white ring-1 ring-inset ring-brand-line">Recording</span>}
        <span aria-hidden="true" className="absolute inset-x-0 bottom-0 h-1 bg-black/60">
          <span className="block h-full bg-accent" style={{ width: `${c.pct}%` }} />
        </span>
      </span>
      <span className="mt-2 block truncate text-sm font-medium text-neutral-100 group-hover:text-accent">{c.title}</span>
      <span className="block truncate text-[12.5px] text-neutral-300">{c.until}</span>
      {c.next && <span className="block truncate text-[12.5px] text-neutral-400">{c.next}</span>}
    </Link>
  )
}
