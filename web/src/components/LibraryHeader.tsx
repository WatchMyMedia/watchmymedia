import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { healthQuery } from '../api/queries'
import { ActivityBar } from './ActivityBar'
import { LibraryActions } from './LibraryActions'
import { HrefLink } from './settings/Frame'
import { settingsHref } from '../lib/settingsRoutes'
import { filesSummary } from '../lib/libraryFiles'
import type { components } from '../api/schema'

type Library = components['schemas']['Library']

/**
 * The top of a library's Files page, the one page for its technical detail:
 * where it sits in Settings, a summary in words, the folder, Scan and Match,
 * and the way back to browsing.
 */
export function LibraryHeader({ library }: { library: Library }) {
  const health = useQuery(healthQuery)
  const tmdb = health.data?.tmdbConfigured ?? false
  const browse = library.type === 'tv' ? 'TV Shows' : 'Movies'
  return (
    <div>
      <nav aria-label="Breadcrumb" className="flex flex-wrap gap-1.5 text-[13px] text-neutral-400">
        <Link to="/settings" className="text-neutral-300 hover:text-accent">
          Settings
        </Link>
        <span aria-hidden="true">›</span>
        <Link to="/settings/libraries" className="text-neutral-300 hover:text-accent">
          Libraries
        </Link>
        <span aria-hidden="true">›</span>
        <HrefLink href={settingsHref('libraries', library.id)} className="text-neutral-300 hover:text-accent">
          {library.name}
        </HrefLink>
        <span aria-hidden="true">›</span>
        <span aria-current="page">Files</span>
      </nav>
      <header className="mt-3.5 flex flex-wrap items-start gap-x-6 gap-y-4">
        <div className="min-w-0 flex-[1_1_420px]">
          <h1 className="text-[26px] font-bold tracking-[-.02em] md:text-[28px]">{library.name} · files</h1>
          <p className="mt-1.5 flex flex-wrap text-[14.5px] text-neutral-300">
            {filesSummary(library).map((p, i) => (
              <span key={p.text} className={p.warn ? 'text-warn' : undefined}>
                {i > 0 && <span className="mx-[9px] text-dim">·</span>}
                {p.text}
              </span>
            ))}
          </p>
          <p className="mt-2 break-all font-mono text-xs text-neutral-400">{library.path}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2.5">
          <LibraryActions library={library} tmdbConfigured={tmdb} large />
          <Link to={library.type === 'tv' ? '/tv' : '/movies'} className="btn btn-ghost">
            Browse {browse} ›
          </Link>
        </div>
      </header>
      <ActivityBar library={library} />
      {health.data && !tmdb && (
        <p className="mt-3 rounded-lg border border-warn/40 bg-warn/10 px-3 py-2 text-[13px] text-neutral-300">
          <strong className="text-neutral-100">Posters and title details are off.</strong> Add a TMDB key in{' '}
          <Link to="/settings/general" className="underline">
            Settings › General
          </Link>
          .
        </p>
      )}
    </div>
  )
}
