import { Link } from '@tanstack/react-router'
import { img } from '../lib/images'
import type { components } from '../api/schema'

type HomeHero = components['schemas']['HomeHero']

export function Hero({ hero }: { hero: HomeHero }) {
  const backdrop = img(hero.backdropPath, 'w1280')
  const target =
    hero.kind === 'movie' && hero.movieId != null
      ? { to: '/movies/$movieId' as const, params: { movieId: String(hero.movieId) }, label: 'Go to movie' }
      : hero.kind === 'episode' && hero.showId != null && hero.episodeId != null
        ? {
            to: '/shows/$showId/seasons/$number/episodes/$episodeId' as const,
            params: { showId: String(hero.showId), number: String(hero.seasonNumber ?? 1), episodeId: String(hero.episodeId) },
            label: 'Go to episode',
          }
        : hero.showId != null
          ? { to: '/shows/$showId' as const, params: { showId: String(hero.showId) }, label: 'Go to show' }
          : null
  return (
    <section className="relative overflow-hidden rounded-xl border border-line bg-surface">
      {backdrop && (
        <div
          className="absolute inset-0 bg-cover bg-center opacity-40"
          style={{ backgroundImage: `url(${backdrop})`, maskImage: 'linear-gradient(to right, black 30%, transparent 95%)' }}
        />
      )}
      <div className="relative flex min-h-[320px] max-w-2xl flex-col justify-end gap-3 p-6 sm:p-10">
        <p className="text-xs font-semibold uppercase tracking-wider text-accent">{hero.kicker}</p>
        <h1 className="text-3xl font-bold sm:text-4xl">{hero.title}</h1>
        {hero.facts && <p className="text-sm text-neutral-300">{hero.facts}</p>}
        {hero.overview && <p className="line-clamp-3 text-sm text-neutral-300">{hero.overview}</p>}
        <div className="mt-2 flex flex-wrap gap-2">
          {hero.fileId != null ? (
            <Link
              to="/play/$fileId"
              params={{ fileId: String(hero.fileId) }}
              search={{ title: hero.kind === 'episode' ? `${hero.kicker.replace('New episode · ', '')} · ${hero.title}` : hero.title, back: '/' }}
              className="rounded-md btn-primary px-4 py-2 text-sm font-semibold"
            >
              ▶ Play
            </Link>
          ) : null}
          {target && (
            <Link to={target.to} params={target.params} className="rounded-md border border-line-2 bg-black/40 px-4 py-2 text-sm font-medium hover:bg-black/60">
              {target.label}
            </Link>
          )}
        </div>
      </div>
    </section>
  )
}
