import { memo, useState, type ReactNode } from 'react'
import type { components } from '../../../src/api/schema'
import { useMediaUrls, useNav } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Banner, Option, SidePanel } from '../../ui/Overlays'
import { Row } from '../../ui/Row'
import { Glyph, type GlyphName } from './Glyph'
import { castLabel } from './movieLabels'
import { titleCard, type RelatedRow } from './relatedCards'
import { toneStyle, type Notice } from './detailWords'

// The parts the movie page and the show page share (the Android TV app's
// DetailParts.kt and ShowPageMore.kt), drawn with the kit's tokens.

type Credit = components['schemas']['Credit']
type ProblemKind = components['schemas']['Problem']['kind']

/** A picture that leaves the placeholder showing when it doesn't load. */
export function Picture({ src, className }: { src?: string; className?: string }) {
  const [failed, setFailed] = useState(false)
  if (!src || failed) return null
  return <img className={className ?? 'dt-img'} src={src} alt="" decoding="async" draggable={false} onError={() => setFailed(true)}/>
}

/** The backdrop behind a page's top, faded into the ground; `kind` places it as the movie or the show board does. */
export const Backdrop = memo(function Backdrop({ path, seed, kind }: { path?: string; seed: string; kind: 'movie' | 'show' }) {
  const media = useMediaUrls()
  return <div className={`dt-backdrop dt-backdrop--${kind}`} style={toneStyle(seed)} aria-hidden="true">
    <Picture src={media.imageUrl(path, 'w1280')}/>
    <span className="dt-backdrop__fade"/>
  </div>
})

/** A poster at the board's size, its placeholder underneath. */
export function Poster({ path, seed, className }: { path?: string; seed: string; className: string }) {
  const media = useMediaUrls()
  return <div className={`dt-poster ${className}`} style={toneStyle(seed)} aria-hidden="true"><Picture src={media.imageUrl(path, 'w342')}/></div>
}

export function Tag({ children, gold }: { children: ReactNode; gold?: boolean }) {
  return <span className={`dt-tag${gold ? ' dt-tag--gold' : ''}`}>{children}</span>
}

/** The facts line: parts between grey dots, then tags. */
export function Facts({ parts, tags = [], children, className = 'dt-facts' }: { parts: string[]; tags?: string[]; children?: ReactNode; className?: string }) {
  return <p className={className}>
    {parts.map((p, i) => <span key={`${i}${p}`}>{i > 0 && <span className="dt-dot">·</span>}{p}</span>)}
    {tags.length > 0 && parts.length > 0 && <span className="dt-dot">·</span>}
    {tags.map((t) => <Tag key={t}>{t}</Tag>)}
    {children}
  </p>
}

/** The remote's keys for the part with focus, under it: "▲ ▼ the versions · OK plays this version". */
export function Hint({ items }: { items: { key: string; label: string }[] }) {
  return <p className="dt-hint" aria-hidden="true">
    {items.map((h, i) => <span key={h.key + h.label}>{i > 0 && ' · '}<span className="tv-kbd">{h.key}</span>{h.label}</span>)}
  </p>
}

/** A gold bar of how far through. */
export function Progress({ value, className = 'dt-bar' }: { value: number; className?: string }) {
  return <span className={className} aria-hidden="true"><span style={{ width: `${Math.round(Math.min(1, value) * 100)}%` }}/></span>
}

/** The ⋯ button: square, beside the page's buttons. */
export function MoreButton({ id, onPress }: { id: string; onPress(): void }) {
  return <span className="dt-sq"><Button id={id} label="More" onPress={onPress}><Glyph name="more"/></Button></span>
}

export type MenuItem = { id: string; label: string; icon: GlyphName; danger?: boolean; onPress(): void }

/**
 * More, from the right (the boards' `aside`): what the viewer may do with
 * the movie or the episode, the dangerous ones last in red under a line.
 * Return closes it, and focus goes back to the ⋯.
 */
export function MorePanel({ kicker, title, items, note, onClose }: { kicker: string; title: string; items: MenuItem[]; note?: string; onClose(): void }) {
  const firstDanger = items.findIndex((i) => i.danger)
  return <SidePanel title={title} onClose={onClose}>
    <p className="dt-panel-kicker">{kicker}</p>
    <div role="menu" aria-label={`More for ${title}`} className="dt-menu">
      {items.map((item, i) => <div key={item.id}>
        {i === firstDanger && i > 0 && <span role="separator" className="dt-menu__line"/>}
        <button type="button" role="menuitem" data-focus={item.id} data-autofocus={i === 0 || undefined} className={`dt-menu__item${item.danger ? ' dt-menu__item--danger' : ''}`} onClick={item.onPress}>
          <Glyph name={item.icon}/>{item.label}
        </button>
      </div>)}
    </div>
    <span className="dt-grow"/>
    <p className="dt-panel-note">{note ?? 'Return closes this panel.'}</p>
  </SidePanel>
}

const KINDS: { value: ProblemKind; label: string }[] = [
  { value: 'video', label: 'Video' }, { value: 'audio', label: 'Audio' }, { value: 'subtitles', label: 'Subtitles' }, { value: 'other', label: 'Other' },
]

/** Report a problem: what kind, sent without a note as the TV's report is (the Android TV app's OptionsDialog). */
export function ReportPanel({ onPick, onClose }: { onPick(kind: ProblemKind): void; onClose(): void }) {
  return <SidePanel title="Report a problem" onClose={onClose}>
    {KINDS.map((k, i) => <Option key={k.value} id={`report/${k.value}`} label={k.label} autoFocus={i === 0} onPress={() => onPick(k.value)}/>)}
    <span className="dt-grow"/>
    <p className="dt-panel-note">Return closes this panel.</p>
  </SidePanel>
}

/** The notice the page's useNotice holds, at the top right: it takes no focus, and Return goes on to the screen, so it still leaves in one step. */
export function NoticeBanner({ notice }: { notice?: Notice }) {
  return notice ? <Banner key={notice.n} icon={notice.problem ? 'alert' : 'check'} title={notice.title}>{notice.body}</Banner> : null
}

/** The cast, a focus stop each; OK does nothing. */
export const CastList = memo(function CastList({ cast, focusable }: { cast: Credit[]; focusable: boolean }) {
  const media = useMediaUrls()
  return <div className="dt-cast">
    {cast.map((c, i) => {
      const body = <>
        <span className="dt-cast__face" style={toneStyle(c.name)}><Picture src={media.imageUrl(c.profilePath, 'w185')}/></span>
        <span className="dt-cast__name">{c.name}</span>
        {c.character && <span className="dt-cast__role">{c.character}</span>}
      </>
      return focusable
        ? <button key={`${i}${c.name}`} type="button" data-focus={`cast/${i}`} className="dt-cast__member" aria-label={castLabel(c)}>{body}</button>
        : <span key={`${i}${c.name}`} className="dt-cast__member">{body}</span>
    })}
  </div>
})

/** One related row: Home's poster cards; OK opens the title's page on top of this one, and Return comes back to the card. */
export const RelatedSection = memo(function RelatedSection({ row, hint }: { row: RelatedRow; hint?: ReactNode }) {
  const media = useMediaUrls()
  const nav = useNav()
  const cards = row.items.map(titleCard)
  return <div className="dt-related" data-vitem data-zone={`related/${row.id}`}>
    <Row id={`related/${row.id}`} title={row.title} subtitle={row.subtitle} variant="poster" items={cards} itemKey={(c) => c.key}
      renderItem={(c, id) => <Card id={id} variant="poster" title={c.title} subtitle={c.subtitle} image={media.imageUrl(c.posterPath, 'w342')} badges={c.badges}
        progress={c.progress} label={c.label} onPress={() => nav.push(c.route)}/>}/>
    {hint}
  </div>
})

/** Cast & details (the Android TV app's DetailsSheet): the whole overview, the facts as rows, and the cast. */
export function DetailsPanel({ title, overview, facts, cast, onClose }: { title: string; overview: string; facts: [string, string][]; cast: Credit[]; onClose(): void }) {
  return <SidePanel title={title} onClose={onClose}>
    {overview && <p className="dt-details__overview">{overview}</p>}
    <dl className="dt-details__facts">
      {facts.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
    </dl>
    {cast.length > 0 && <><h3 className="dt-h3">Cast</h3><CastList cast={cast.slice(0, 8)} focusable={false}/></>}
    <span className="dt-grow"/>
    <div className="tv-actions"><Button id="details/close" autoFocus onPress={onClose}>Close</Button></div>
  </SidePanel>
}
