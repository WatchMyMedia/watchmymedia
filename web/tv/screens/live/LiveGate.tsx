import { problemWords } from '../../ui/problemWords'
import { ProblemScreen, SkeletonRow, SkeletonText } from '../../ui/States'
import type { useLiveTV } from './liveData'

/** Live TV can't load or isn't there: the problem and what to do. Undefined while there is nothing wrong. */
export function LiveGate({ caps, channels, available }: ReturnType<typeof useLiveTV>) {
  if (caps.isPending || (available && channels.isPending)) return <div className="live-home__loading"><SkeletonText width="18rem"/><SkeletonRow variant="live"/></div>
  const failed = caps.error ?? channels.error
  if (failed) {
    const w = problemWords(failed)
    return <ProblemScreen icon="offline" title="Couldn’t load Live TV" actions={[{ id: 'live/retry', label: 'Try again', onPress: () => { void caps.refetch(); void channels.refetch() } }]}>{w.body}</ProblemScreen>
  }
  if (!available) return <ProblemScreen icon="live" title="Live TV isn’t available" actions={[{ id: 'live/retry', label: 'Check again', onPress: () => void caps.refetch() }]}>
    This server or account can’t watch Live TV. An admin manages tuners and access in the web app.
  </ProblemScreen>
  if (!channels.data?.items.length) return <ProblemScreen icon="live" title="No channels yet" actions={[{ id: 'live/retry', label: 'Reload channels', onPress: () => void channels.refetch() }]}>
    An admin can refresh the tuner’s lineup and turn channels on in the web app.
  </ProblemScreen>
  return null
}
