import { useRef, useState, type CSSProperties } from 'react'
import { useHold } from '../app/hooks'
import { Icon, PlayGlyph } from './Icon'
import { placeholderTone } from './tones'

export type CardVariant = 'poster' | 'wide' | 'episode' | 'continue' | 'live'

export type CardBadge = { label: string; tone?: 'gold' | 'live' }

export type CardProps = {
  /** The data-focus id: stable across redraws (a row uses `<row>/<item>`). */
  id: string
  variant: CardVariant
  title: string
  subtitle?: string
  /** The server the title is on, when that isn't the one the app is on (Search): a line under the title. */
  server?: string
  /** A full address from useMediaUrls().imageUrl (w342 for posters, w780 for wide cards). */
  image?: string
  /** How far through, 0–1: the gold bar along the bottom. */
  progress?: number
  badges?: CardBadge[]
  /** Watched all the way: a white tick in the corner. */
  done?: boolean
  /** The chosen one of a row (the episode on top of a show's page): a gold bar above it, never a ring. */
  selected?: boolean
  /** A live channel's card: its number, name and what is on. */
  live?: { number: string; name: string; now?: string }
  /** Names the whole target for screen readers ("Harbor Lights, season 3 episode 7"). */
  label?: string
  onPress?: () => void
  onHold?: () => void
}

/** A picture that falls back to the card's placeholder when it doesn't load. */
function Picture({ src }: { src: string }) {
  const [failed, setFailed] = useState(false)
  if (failed) return null
  return <img className="tv-card__img" src={src} alt="" decoding="async" loading="lazy" draggable={false} onError={() => setFailed(true)}/>
}

/**
 * The cards rows and grids are made of: poster (2:3), wide (16:9), episode,
 * continue (with a play mark and progress) and live (a channel). Focus
 * draws the gold ring around the picture and grows it; the title and line
 * under it stay put.
 */
export function Card({ id, variant, title, subtitle, server, image, progress, badges, done, selected, live, label, onPress, onHold }: CardProps) {
  const ref = useRef<HTMLButtonElement>(null)
  useHold(ref, onHold)
  const tone = placeholderTone(title)
  return <button ref={ref} type="button" data-focus={id} className={`tv-card tv-card--${variant}${selected ? ' tv-card--selected' : ''}`} aria-current={selected || undefined} aria-label={label ?? [title, server && `on ${server}`, subtitle].filter(Boolean).join(', ')} onClick={onPress}>
    <span className="tv-card__art" style={{ '--ph-a': tone[0], '--ph-b': tone[1] } as CSSProperties}>
      {live ? <>
        <span className="tv-card__channel"><b>{live.number}</b><span>{live.name}</span></span>
        <span className="tv-card__now">{live.now ?? title}</span>
      </> : <>
        <span className="tv-card__placeholder" aria-hidden="true"/>
        {image && <Picture key={image} src={image}/>}
        {variant === 'continue' && <span className="tv-card__play" aria-hidden="true"><span><PlayGlyph/></span></span>}
      </>}
      {done && <span className="tv-card__done" aria-hidden="true"><Icon name="check"/></span>}
      {!!badges?.length && <span className="tv-card__badges">{badges.map((b) => <span key={b.label} className={`tv-badge${b.tone ? ` tv-badge--${b.tone}` : ''}`}>{b.label}</span>)}</span>}
      {progress !== undefined && progress > 0 && <span className="tv-card__progress"><span style={{ width: `${Math.min(100, Math.round(progress * 100))}%` }}/></span>}
    </span>
    {!live && <span className="tv-card__title">{title}</span>}
    {!live && server && <span className="tv-card__server"><Icon name="server"/><span>{server}</span></span>}
    {!live && subtitle && <span className="tv-card__sub">{subtitle}</span>}
    {live && subtitle && <span className="tv-card__sub" style={{ marginTop: '1.375rem' }}>{subtitle}</span>}
  </button>
}
