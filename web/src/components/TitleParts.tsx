import type { ReactNode } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Link, type LinkProps } from '@tanstack/react-router'
import { historyQuery } from '../api/activity'
import { useAuth } from '../authContext'
import { relativeTime } from '../lib/format'
import { img } from '../lib/images'
import { historyWords, initials } from '../lib/titlePage'
import { ChevronDownIcon, ChevronLeftIcon } from './icons'

// The pieces a movie's and a show's page are built from: the band at the top
// with the backdrop, the choices made before playing, the cast, the side
// panels and the way back.

/**
 * The band at the top of a title's page: the backdrop, faded to the ground
 * and darkened behind the words, from edge to edge of the window.
 */
export function TitleBand({ backdropPath, children }: { backdropPath?: string; children: ReactNode }) {
  const backdrop = img(backdropPath, 'w1280')
  return (
    // Above what follows, so its menus open over the page; the picture alone is clipped.
    <div className="relative isolate z-10 -mx-[clamp(16px,3.4vw,48px)] -mt-5">
      <div aria-hidden="true" className="absolute inset-0 -z-10 overflow-hidden bg-gradient-to-b from-surface-2 to-ground">
        {backdrop && <img src={backdrop} alt="" className="absolute inset-0 size-full object-cover object-top opacity-45" />}
        <span className="absolute inset-0 bg-[linear-gradient(90deg,rgba(15,15,16,.78)_0%,rgba(15,15,16,.4)_55%,rgba(15,15,16,.1)_100%)]" />
        <span className="absolute inset-x-0 bottom-0 h-2/5 bg-gradient-to-b from-transparent to-ground" />
      </div>
      <div className="px-[clamp(16px,3.4vw,48px)] pb-8 pt-5 md:pb-9">{children}</div>
    </div>
  )
}

/** "‹ Movies": the way back to the section, at the top of the band. */
export function BackLink({ link, label }: { link: LinkProps; label: string }) {
  return (
    <Link {...link} className="inline-flex min-h-9 items-center gap-1.5 text-[13.5px] text-neutral-300 hover:text-accent">
      <ChevronLeftIcon size={16} />
      {label}
    </Link>
  )
}

/** One line of facts with a dot between each; a content rating drawn as a tag. */
export function FactsLine({ facts, rating, className = '' }: { facts: string[]; rating?: string; className?: string }) {
  const [first, ...rest] = facts.filter(Boolean)
  return (
    <p className={`meta ${className}`}>
      {first && <span>{first}</span>}
      {rest.slice(0, 1).map((f) => (
        <span key={f}>{f}</span>
      ))}
      {rating && (
        <span>
          <span className="tag text-xs" aria-label={`Rated ${rating}`}>
            {rating}
          </span>
        </span>
      )}
      {rest.slice(1).map((f) => (
        <span key={f}>{f}</span>
      ))}
    </p>
  )
}

/** A choice made before playing: Version, Sound or Subtitles, as a native select with its name before it. */
export function ChoiceSelect({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: string
  options: { value: string; label: string; disabled?: boolean }[]
  onChange: (value: string) => void
}) {
  return (
    <label className="relative inline-flex min-h-[34px] max-w-full cursor-pointer items-center gap-2 rounded-lg border border-line-2 bg-ground/55 pl-3 text-[13px] text-neutral-300 focus-within:border-accent hover:border-line-3 max-md:min-h-10">
      <span className="text-neutral-400">{label}</span>
      <select
        aria-label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="min-w-0 max-w-[16rem] cursor-pointer appearance-none truncate border-0 bg-transparent py-1.5 pr-8 font-semibold text-neutral-100 outline-none"
      >
        {options.map((o) => (
          <option key={o.value} value={o.value} disabled={o.disabled} className="bg-surface-2 text-neutral-100">
            {o.label}
          </option>
        ))}
      </select>
      <ChevronDownIcon size={15} className="pointer-events-none absolute right-2.5 text-neutral-400" />
    </label>
  )
}

/**
 * The cast as cards: a photo (or initials) with the name and the part. Not
 * links yet: opening a person's movies and shows needs the server to keep
 * each person's TMDB id with the credit.
 */
export function PeopleRow({ people, heading = 'Cast', note, small = false }: { people: { name: string; character?: string; profilePath?: string }[]; heading?: string; note?: string; small?: boolean }) {
  if (!people.length) return null
  return (
    <section aria-label={heading}>
      <div className="rowh">
        <h2 className={small ? 'text-[17px]!' : ''}>{heading}</h2>
        {note && <span className="note">{note}</span>}
      </div>
      <ul className="scrollbar-hide -mx-1 mt-3.5 flex gap-3 overflow-x-auto px-1 pb-1.5 pt-1 md:gap-4">
        {people.map((p, i) => (
          <li key={`${p.name}-${i}`} className={`shrink-0 ${small ? 'w-[100px] md:w-[112px]' : 'w-[104px] md:w-[124px]'}`}>
            <span aria-hidden="true" className="grid aspect-[4/5] place-items-center overflow-hidden rounded-[10px] bg-surface-2 text-[26px] font-medium tracking-[.04em] text-neutral-500 ring-1 ring-inset ring-white/[.07]">
              {p.profilePath ? <img src={`/api/images/w185${p.profilePath}`} alt="" loading="lazy" className="size-full object-cover" /> : initials(p.name)}
            </span>
            <span className="mt-2 block truncate text-sm font-medium">{p.name}</span>
            {p.character && <span className="block truncate text-[12.5px] text-neutral-400">{p.character}</span>}
          </li>
        ))}
      </ul>
    </section>
  )
}

/** A side panel of label and value pairs; rows with no value are left out. */
export function DetailsPanel({ title, rows }: { title: string; rows: [string, ReactNode][] }) {
  const shown = rows.filter(([, v]) => v !== '' && v != null && v !== false)
  if (!shown.length) return null
  const id = `panel-${title.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <section aria-labelledby={id} className="panel p-[18px]">
      <h2 id={id} className="text-[15px] font-semibold">
        {title}
      </h2>
      <dl className="dl mt-3.5">
        {shown.map(([k, v]) => (
          <div key={k} className="contents">
            <dt>{k}</dt>
            <dd className="min-w-0 break-words">{v}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

/**
 * Watched here: where this person is, then the latest plays of this title
 * (an admin sees the household's; anyone else their own), each with when and
 * on what, and the way to the full history.
 */
export function WatchedHere({ movie, show, you }: { movie?: number; show?: number; you?: string }) {
  const auth = useAuth()
  const admin = auth.user?.isAdmin ?? false
  const history = useInfiniteQuery(historyQuery(movie ? { movie } : { show }, 5))
  const page = history.data?.pages[0]
  const rows = page?.items ?? []
  if (!you && !rows.length) return null
  const me = auth.user?.id
  const initial = (name: string) => name.charAt(0).toUpperCase()
  return (
    <section aria-labelledby="watched-here" className="panel p-[18px]">
      <h2 id="watched-here" className="text-[15px] font-semibold">
        {admin ? 'Watched here' : 'Your history'}
      </h2>
      <ul className="mt-3.5 flex flex-col gap-3">
        {you && (
          <li className="flex items-center gap-2.5">
            <span aria-hidden="true" className="grid size-[26px] shrink-0 place-items-center rounded-full bg-brand text-[11.5px] font-semibold text-white ring-1 ring-inset ring-white/20">
              {initial(auth.user?.name ?? '')}
            </span>
            <span className="text-[13.5px]">{you}</span>
          </li>
        )}
        {rows.map((r) => (
          <li key={r.id} className="flex items-center gap-2.5">
            <span aria-hidden="true" className={`grid size-[26px] shrink-0 place-items-center rounded-full text-[11.5px] font-semibold text-white ring-1 ring-inset ring-white/20 ${r.user.id === me ? 'bg-brand' : 'bg-raise'}`}>
              {initial(r.user.name)}
            </span>
            <span className="min-w-0 text-[13.5px]">
              <span className="block">{historyWords(r, r.user.id === me)}</span>
              <span className="block truncate text-[12.5px] text-neutral-400">{[relativeTime(r.startedAt), r.device?.name].filter(Boolean).join(' · ')}</span>
            </span>
          </li>
        ))}
      </ul>
      {rows.length > 0 && (
        <Link to="/activity/history" search={movie ? { movie } : { show }} className="mt-3.5 inline-block text-[13px] text-neutral-300 hover:text-accent">
          Full history ›
        </Link>
      )}
    </section>
  )
}
