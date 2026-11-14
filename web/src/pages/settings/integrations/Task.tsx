import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { integrationsQuery, type Integration } from '../../../api/integrations'
import { FlowFrame } from '../../../components/settings/Flow'
import { LoadingRows, Message } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { APP, TASK_LABEL, shortVersion, taskFits, things, type ConnectionTask } from '../../../lib/integrationsSettings'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { hubHref, pageHref, setFlash, useUpsertConnection } from './shared'
import { ConnectStep, FoldersStep, PeopleStep, RequestsStep, WebhookStep, type PeopleSummary } from './steps'

const leaveWhat = 'What you changed here isn’t saved.'

/**
 * One connection's step by itself (§5.2): the same screen as in the Add
 * flow, with Cancel and Save, and a banner on the connection's page after.
 */
export function ConnectionTaskPage({ task, id }: { task: string; id: number }) {
  const go = useGo()
  const upsert = useUpsertConnection()
  const list = useQuery(integrationsQuery)
  const c = list.data?.items.find((x) => x.id === id)
  const page = pageHref(id)
  const fits = c ? taskFits(task, c.kind) : true
  useEffect(() => {
    if (c && !fits) go(page, { replace: true })
  }, [c, fits, go, page])

  if (!c || !fits) {
    const crumbs = settingsCrumbs('integrations', { label: TASK_LABEL[task as ConnectionTask] ?? 'Connection' })
    return (
      <FlowFrame tab="integrations" crumbs={crumbs} title={list.isError || (list.data && !c) ? 'This connection isn’t here any more' : 'Loading…'}
        next={list.data && !c ? { label: 'Back to Integrations', href: hubHref } : undefined}>
        {list.data && !c ? <Message tone="info">It may have been removed in another tab. Integrations lists what is connected now.</Message> : <LoadingRows rows={2} />}
      </FlowFrame>
    )
  }

  const t = task as ConnectionTask
  const crumbs = settingsCrumbs('integrations', { label: c.name, href: page }, { label: TASK_LABEL[t] })
  const frame = { tab: 'integrations' as const, crumbs, back: { label: 'Cancel', href: page } }
  const done = (title: string, body: string, saved?: Integration) => {
    if (saved) upsert(saved)
    setFlash({ href: page, title, body })
    go(page)
  }
  const app = APP[c.kind]
  const word = things(c.kind)
  switch (t) {
    case 'connection':
      return (
        <ConnectStep kind={c.kind} connection={c} frame={frame} nextLabel="Save" leaveWhat={leaveWhat}
          onSaved={(saved) => done('Connection saved', `${[app, shortVersion(saved.status.version)].filter(Boolean).join(' ')} answered, and this server uses the new details from now on.`, saved)} />
      )
    case 'folders':
      return (
        <FoldersStep connection={c} frame={frame} nextLabel="Save" leaveWhat={leaveWhat}
          onSaved={(saved) => done('Folders saved', saved.pathMap.length
            ? `${saved.pathMap.length === 1 ? '1 folder' : `${saved.pathMap.length} folders`} mapped. Files ${app} imports from now on match exactly.`
            : `No folders mapped: this server reads ${app}’s folders as they are.`, saved)} />
      )
    case 'requests':
      return (
        <RequestsStep connection={c} inFlow={false} frame={frame} nextLabel="Save" leaveWhat={leaveWhat}
          onSaved={(saved) => (saved.targets[0]
            ? done('Request target saved', `Every ${word.one} request goes to ${saved.targets[0].rootFolder} from now on.`, saved)
            : done(`Requests for ${word.many} are off`, `People can’t request ${word.many} until a target is chosen.`, saved))} />
      )
    case 'webhook':
      return (
        <WebhookStep connection={c as Integration & { kind: 'radarr' | 'sonarr' }} frame={frame} nextLabel="Connect webhook" leaveWhat={leaveWhat}
          onSaved={(saved) => done('Webhook connected', `${app} sent a test event, so imports and renames show up at once from now on.`, saved)} />
      )
    case 'people':
      return <PeopleStep frame={frame} nextLabel="Save" leaveWhat={leaveWhat} onSaved={(s: PeopleSummary) => done('Links saved', `${s.linked} of ${s.total} people request through Seerr.`)} />
  }
}

