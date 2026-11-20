import { FlowFrame } from '../../components/settings/Flow'
import { useSettingsPage } from '../../components/settings/hooks'
import { ServerOnly } from './SettingsLayout'
import { useSettingsAccess } from './shared'
import { ConnectFlow } from './plex/ConnectFlow'
import { PlexHub } from './plex/Hub'
import { ImportFlow } from './plex/ImportFlow'
import { LinkFlow } from './plex/LinkFlow'
import { RestoreFlow } from './plex/RestoreFlow'
import { UndoFlow } from './plex/UndoFlow'
import { plexCrumbs, TASK_LABEL } from './plex/state'

/**
 * Settings › Server › Plex: a hub that says
 * where things stand and what comes next, and a stepped flow per task, each
 * at its own address under /settings/plex. Personal imported history has its
 * own page under Activity.
 */
export function PlexSettings() {
  const page = useSettingsPage()
  const { admin } = useSettingsAccess()
  if (page?.kind === 'flow') {
    const task = (page.task in TASK_LABEL ? page.task : 'connect') as keyof typeof TASK_LABEL
    // A member, or an admin the gate keeps out: the frame says why, and no
    // Plex request is made.
    if (!admin) return <FlowFrame tab="plex" crumbs={plexCrumbs(task)} title={TASK_LABEL[task]} />
    const { step, run, rest } = page.params
    switch (page.task) {
      case 'connect':
        return <ConnectFlow step={step} />
      case 'link':
        return <LinkFlow step={step} />
      case 'import':
        return <ImportFlow step={step} run={rest} />
      case 'undo':
        return <UndoFlow run={run} step={step} />
      case 'restore':
        return <RestoreFlow step={step} />
    }
  }
  return (
    <ServerOnly tab="plex">
      <PlexHub />
    </ServerOnly>
  )
}
