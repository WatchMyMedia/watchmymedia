import { useBack, useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { Placeholder } from '../Placeholder'

/**
 * The player, until the player feature draws its controls over the engine
 * in player/. It gets what to play as `route.play` and leaves with nav.back().
 */
export function PlayerScreen({ route }: ScreenProps<'player'>) {
  const nav = useNav()
  useBack(() => { nav.back(); return true })
  const what = route.play.kind === 'file' ? route.play.title ?? `File ${route.play.fileId}` : route.play.kind === 'live' ? `Channel ${route.play.channelId}`
    : route.play.kind === 'recording' ? route.play.title ?? 'A recording' : route.play.title ?? 'A trailer'
  return <Placeholder full kicker={`Player · ${route.play.kind} · placeholder`} title={what}>
    The player engine (player/) plays this; its controls, pickers and Next episode come with the player feature.
  </Placeholder>
}
