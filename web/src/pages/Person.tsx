import { useQuery } from '@tanstack/react-query'
import { Link, useCanGoBack, useRouter } from '@tanstack/react-router'
import { personQuery } from '../api/queries'
import { ApiError } from '../api/client'
import type { components } from '../api/schema'
import { LibraryCard } from '../components/LibraryCard'
import { ChevronLeftIcon } from '../components/icons'
import { initials } from '../lib/titlePage'
import { onlyTitle, personCountLine, roleWords } from '../lib/personPage'

type PersonDetail = components['schemas']['PersonDetail']
type PersonTitle = components['schemas']['PersonTitle']

/**
 * A person from a title's cast: their movies and shows in this library, one
 * card a title, newest first, each saying who they play. Opened by the TMDB
 * person id, so two people who share a name stay apart.
 */
export function Person({ personId }: { personId: number }) {
  const person = useQuery(personQuery(personId))
  return (
    <div>
      <Back />
      {person.isPending && <p className="mt-6 text-neutral-400">Loading…</p>}
      {person.isError &&
        (person.error instanceof ApiError && person.error.status === 404 ? (
          <p className="mt-6 text-neutral-300">None of this person’s movies or shows are in your library now.</p>
        ) : (
          <p className="mt-6 text-red-400">{person.error.message}</p>
        ))}
      {person.data && <PersonView p={person.data} />}
    </div>
  )
}

/** The way back to the title the cast was on, or Home when the page was opened on its own. */
function Back() {
  const router = useRouter()
  const canGoBack = useCanGoBack()
  const className = 'inline-flex min-h-9 items-center gap-1.5 text-[13.5px] text-neutral-300 hover:text-accent'
  return canGoBack ? (
    <button type="button" onClick={() => router.history.back()} className={className}>
      <ChevronLeftIcon size={16} />
      Back
    </button>
  ) : (
    <Link to="/" className={className}>
      <ChevronLeftIcon size={16} />
      Home
    </Link>
  )
}

function PersonView({ p }: { p: PersonDetail }) {
  const one = onlyTitle(p)
  return (
    <>
      <header className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-4">
        <span aria-hidden="true" className="grid size-[104px] shrink-0 place-items-center overflow-hidden rounded-full bg-surface-2 text-[30px] font-medium tracking-[.04em] text-neutral-500 ring-1 ring-inset ring-white/10 md:size-[132px]">
          {p.profilePath ? <img src={`/api/images/w185${p.profilePath}`} alt="" className="size-full object-cover" /> : initials(p.name)}
        </span>
        <div className="min-w-0">
          <h1 className="text-[26px] font-bold tracking-[-.02em] md:text-[34px]">{p.name}</h1>
          <p className="mt-1 text-sm text-neutral-300">{personCountLine(p.movies.length, p.shows.length)}</p>
        </div>
      </header>
      <Titles heading="Movies" items={p.movies} />
      <Titles heading="TV Shows" items={p.shows} />
      {one && (
        <p className="mt-8 max-w-[60ch] text-sm text-neutral-400">
          {one.title} is the only title here with {p.name}. When another of their movies or shows is added to your library, it appears on this page.
        </p>
      )}
    </>
  )
}

function Titles({ heading, items }: { heading: string; items: PersonTitle[] }) {
  if (!items.length) return null
  const id = `person-${heading.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <section aria-labelledby={id} className="mt-8 md:mt-10">
      <div className="rowh">
        <h2 id={id}>{heading}</h2>
        <span className="note">{items.length.toLocaleString()}</span>
      </div>
      <ul className="mt-3.5 grid grid-cols-[repeat(auto-fill,minmax(min(104px,42%),1fr))] gap-x-3 gap-y-[18px] md:grid-cols-[repeat(auto-fill,minmax(min(152px,42%),1fr))] md:gap-x-4 md:gap-y-[22px]">
        {items.map((it) => (
          <li key={`${it.kind}-${it.id}`} className="min-w-0">
            <LibraryCard item={it} role={roleWords(it.character)} />
          </li>
        ))}
      </ul>
    </section>
  )
}
