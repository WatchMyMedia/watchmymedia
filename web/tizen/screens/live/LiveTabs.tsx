import type { ReactNode } from 'react'
import { useNow } from './clock'
import { clockWords } from './liveWords'
import type { LiveTab } from './sectionModel'

/** Live TV's tabs and what sits at the right of their row (the guide's buttons, the room left for recordings). */
export function LiveTabs({ tab, tabs, recordingNow, onTab, children }: {
  tab: LiveTab
  tabs: readonly { id: LiveTab; label: string }[]
  /** How many record now: a red count beside Recordings. */
  recordingNow?: number
  onTab(tab: LiveTab): void
  children?: ReactNode
}) {
  const now = useNow()
  return <nav className="live-tabs" aria-label="Live TV">
    <div className="live-tabs__tabs">
      {tabs.map((t) => <button key={t.id} type="button" className="live-tab" data-focus={`live-tab/${t.id}`} aria-current={t.id === tab ? 'page' : undefined}
        aria-label={t.id === 'recordings' && recordingNow ? `${t.label}, ${recordingNow} recording now` : t.label} onClick={() => onTab(t.id)}>
        {t.label}{t.id === 'recordings' && !!recordingNow && <span className="live-reccount" aria-hidden="true"><i/>{recordingNow}</span>}
      </button>)}
    </div>
    <span className="live-tabs__fill"/>
    {children}
    <span className="live-tabs__clock" aria-hidden="true">{clockWords(now)}</span>
  </nav>
}

export type TabsProps = {
  tab: LiveTab
  tabs: readonly { id: LiveTab; label: string }[]
  recordingNow?: number
  onTab(tab: LiveTab): void
  /** The person chose this tab here: focus stays on it rather than going to where the tab starts. */
  chosen?: boolean
}

