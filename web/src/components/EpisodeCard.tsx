import { Badge } from './Badge'
import { CardLine, CardPicture, CardTitle } from './CardParts'
import { Poster } from './Poster'
import { seCode } from '../lib/format'
import type { components } from '../api/schema'
import { episodeLinks } from '../lib/cardLinks'

export type EpisodeCardData = components['schemas']['EpisodeCard']

/**
 * 16:9 still, 304px wide, for Recently aired rows. The show's title opens
 * the show, "S03" the season, "E07" and the still the season at this
 * episode (lib/cardLinks.ts, PLAN-HOME.md §6); the third line says when it aired.
 */
export function EpisodeCard({ ep }: { ep: EpisodeCardData }) {
  const code = seCode(ep.seasonNumber, ep.episodeNumber)
  const links = episodeLinks(ep)
  return (
    <div className="w-[304px] shrink-0 snap-start">
      <CardPicture {...links.picture}>
        <Poster path={ep.stillPath ?? ep.showBackdropPath} size="w300" alt={ep.title} ratio="aspect-video" />
        <Badge className="absolute bottom-1.5 left-1.5">{code.replace('E', ' · E')}</Badge>
        {!ep.hasFile && <Badge tone="warn" className="absolute right-1.5 top-1.5">Not in library</Badge>}
        {ep.hasFile && ep.isNew && <Badge tone="accent" className="absolute left-1.5 top-1.5">New</Badge>}
      </CardPicture>
      <CardTitle {...links.title} />
      <CardLine segments={links.line} className="text-neutral-300" />
      {links.third && <CardLine segments={[{ text: links.third }]} />}
    </div>
  )
}
