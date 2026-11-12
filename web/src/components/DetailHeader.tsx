import { Link, type LinkProps } from '@tanstack/react-router'
import { Poster } from './Poster'
import { fileType } from '../lib/format'
import { img } from '../lib/images'

/** Backdrop band with breadcrumb, overlapping poster, title, facts and actions. */
export function DetailHeader({
  backdropPath,
  posterPath,
  title,
  year,
  crumbs,
  facts,
  pill,
  tagline,
  overview,
  actions,
}: {
  backdropPath?: string
  posterPath?: string
  title: string
  year?: number
  crumbs: { label: string; link?: LinkProps }[]
  facts: string[]
  pill?: { label: string; tone: 'accent' | 'warn' | 'neutral' }
  tagline?: string
  overview: string
  actions: React.ReactNode
}) {
  const backdrop = img(backdropPath, 'w1280')
  const pillTone = { accent: 'border-accent/60 text-accent', warn: 'border-warn/60 text-warn', neutral: 'border-line-2 text-neutral-300' }
  return (
    <section>
      <div className="relative -mx-4 h-56 overflow-hidden md:-mx-12 md:h-72">
        {backdrop && <div className="absolute inset-0 bg-cover bg-center opacity-50" style={{ backgroundImage: `url(${backdrop})` }} />}
        <div className="absolute inset-0 bg-gradient-to-b from-black/10 via-black/40 to-ground" />
        <nav className="relative px-4 pt-4 text-sm text-neutral-300 md:px-12">
          {crumbs.map((c, i) => (
            <span key={c.label}>
              {i > 0 && ' / '}
              {c.link ? (
                <Link {...c.link} className="hover:text-neutral-100">
                  {c.label}
                </Link>
              ) : (
                c.label
              )}
            </span>
          ))}
        </nav>
      </div>
      <div className="relative -mt-24 flex flex-col gap-6 sm:flex-row">
        <div className="w-36 shrink-0 sm:w-48">
          <Poster path={posterPath} size="w342" alt={title} className="shadow-xl ring-1 ring-line-2" />
        </div>
        <div className="min-w-0 flex-1 sm:pt-20">
          <h1 className="text-3xl font-bold sm:text-4xl">
            {title} {year ? <span className="font-normal text-neutral-400">({year})</span> : null}
          </h1>
          <p className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-neutral-300">
            {facts.filter(Boolean).map((f, i) => (
              <span key={f}>
                {i > 0 && <span className="mr-2 text-neutral-600">·</span>}
                {f}
              </span>
            ))}
            {pill && <span className={`ml-1 rounded-full border px-2 py-0.5 text-xs ${pillTone[pill.tone]}`}>{pill.label}</span>}
          </p>
          {tagline && <p className="mt-3 italic text-neutral-400">{tagline}</p>}
          {overview && <p className={`${tagline ? 'mt-2' : 'mt-4'} max-w-3xl text-neutral-200`}>{overview}</p>}
          <div className="mt-5 flex flex-wrap gap-2">{actions}</div>
        </div>
      </div>
    </section>
  )
}

export function PrimaryButton({ children, disabled, title }: { children: React.ReactNode; disabled?: boolean; title?: string }) {
  return (
    <button type="button" disabled={disabled} title={title} className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50">
      {children}
    </button>
  )
}

export function SecondaryButton({ children, disabled, title, onClick }: { children: React.ReactNode; disabled?: boolean; title?: string; onClick?: () => void }) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={title}
      onClick={onClick}
      className="min-h-[40px] rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 disabled:opacity-50"
    >
      {children}
    </button>
  )
}

export function SideCard({ title, rows }: { title: string; rows: [string, React.ReactNode][] }) {
  const visible = rows.filter(([, v]) => v !== '' && v != null && v !== false)
  if (visible.length === 0) return null
  return (
    <section className="rounded-lg border border-line bg-surface p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-500">{title}</h3>
      <dl className="mt-2 space-y-1.5 text-sm">
        {visible.map(([k, v]) => (
          <div key={k} className="flex justify-between gap-3">
            <dt className="text-neutral-500">{k}</dt>
            <dd className="min-w-0 break-all text-right text-neutral-200">{v}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

export function CastRow({ cast }: { cast: { name: string; character?: string; profilePath?: string }[] }) {
  if (cast.length === 0) return null
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">Cast</h2>
      <div className="scrollbar-hide flex gap-4 overflow-x-auto pb-2">
        {cast.map((c) => (
          <div key={c.name} className="w-28 shrink-0">
            <div className="aspect-[2/3] w-full overflow-hidden rounded-md bg-surface-2">
              {c.profilePath && <img src={`/api/images/w185${c.profilePath}`} alt={c.name} loading="lazy" className="h-full w-full object-cover" />}
            </div>
            <div className="mt-1 truncate text-sm">{c.name}</div>
            <div className="truncate text-xs text-neutral-500">{c.character}</div>
          </div>
        ))}
      </div>
    </section>
  )
}

export function FileTags({ file }: { file: { width?: number; height?: number; videoCodec?: string; audioCodec?: string; relativePath: string; hdr?: string; dvProfile?: number } }) {
  const res = file.height ? (file.height >= 2000 ? '4K' : file.height >= 1000 ? '1080p' : file.height >= 700 ? '720p' : 'SD') : ''
  const hdr = file.hdr === 'dolbyvision' ? (file.dvProfile ? `DV P${file.dvProfile}` : 'Dolby Vision') : file.hdr === 'hdr10' ? 'HDR10' : file.hdr === 'hlg' ? 'HLG' : ''
  const tags = [res, file.videoCodec?.toUpperCase(), hdr, file.audioCodec?.toUpperCase(), fileType(file.relativePath)].filter(Boolean) as string[]
  return (
    <span className="inline-flex flex-wrap gap-1">
      {tags.map((t) => (
        <span key={t} className="rounded border border-line-2 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-neutral-300">
          {t}
        </span>
      ))}
    </span>
  )
}
