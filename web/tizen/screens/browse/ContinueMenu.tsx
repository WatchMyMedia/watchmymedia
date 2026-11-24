import { continueTarget, type ContinueTarget } from '../../../src/lib/continueWatching'
import type { TaggedContinue } from './oneCardHome'
import { useNav } from '../../app/hooks'
import { MorePanel, type MenuItem } from '../details/DetailParts'
import { continueMenuWords } from './continueWords'
import { resumeLine } from './homePages'


/**
 * A Continue watching card's actions, from Hold OK on it or on the resume
 * hero's Resume (API level 34): its page, Start over, and Remove from
 * Continue watching. Removing keeps the place, and the card comes back when
 * the title plays again; the panel says so. The panel closes at once;
 * `onRemove` (whoever opened it, which stays) sends the removal and says how
 * it went.
 */
export function ContinueMenu({ item, onRemove, onClose }: { item: TaggedContinue; onRemove(target: ContinueTarget): void; onClose(): void }) {
  const nav = useNav()
  const words = continueMenuWords(item)
  const target = continueTarget(item)
  // A card from another server plays from there, and its page opens in place when only that server has it.
  const from = item.oneCard ? { server: item.oneCard.server.id } : {}
  const only = item.oneCard?.where === 'only' ? from : {}
  const request = { kind: 'file' as const, fileId: item.fileId, title: item.title, subtitle: resumeLine(item) || undefined, backdropPath: item.backdropPath ?? item.stillPath, ...from }
  const here = item.oneCard?.here
  const page = here?.showId ? { name: 'show' as const, id: here.showId } : here?.movieId ? { name: 'movie' as const, id: here.movieId }
    : item.showId ? { name: 'show' as const, id: item.showId, episodeId: item.episodeId, ...only } : item.movieId ? { name: 'movie' as const, id: item.movieId, ...only } : undefined
  const items: MenuItem[] = [
    ...(page ? [{ id: 'cw/open', label: words.open, icon: 'open' as const, onPress: () => { onClose(); nav.push(page) } }] : []),
    { id: 'cw/restart', label: 'Start over', icon: 'replace', onPress: () => { onClose(); nav.play({ ...request, startMs: 0 }) } },
    ...(target ? [{ id: 'cw/remove', label: 'Remove from Continue watching', icon: 'trash' as const, onPress: () => { onClose(); onRemove(target) } }] : []),
  ]
  return <MorePanel kicker="Continue watching" title={item.title} items={items} note={words.note} onClose={onClose}/>
}
