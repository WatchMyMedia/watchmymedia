import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useRouterState } from '@tanstack/react-router'
import { integrationQuery, integrationsQuery, seerrUsersQuery, type Integration } from '../../../api/integrations'
import { usersQuery } from '../../../api/queries'
import { requestSettingsQuery } from '../../../api/requests'
import { FlowFrame } from '../../../components/settings/Flow'
import { HrefLink, LoadingRows, Outcome } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import {
  APP,
  ARR_STEPS,
  SEERR_STEPS,
  addArrStep,
  arrOutcome,
  connectionChecklist,
  seerrStep,
  shortVersion,
  todoKeys,
} from '../../../lib/integrationsSettings'
import { unmappedRoots } from '../../../lib/integrations'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { hubHref, useUpsertConnection } from './shared'
import { ConnectStep, FoldersStep, PeopleStep, RequestsStep, WebhookStep } from './steps'

function useConnectionId() {
  const search = useRouterState({ select: (s) => s.location.searchStr })
  const id = Number(new URLSearchParams(search).get('id'))
  return Number.isInteger(id) && id > 0 ? id : undefined
}

/**
 * Add Radarr or Sonarr: Connect → Folders → Requests → Webhook →
 * Connected. Connect saves the connection; its id rides in the address from
 * then on, so a reload stays put and Exit leaves the rest to do on its page.
 */
export function AddArrFlow({ kind, step }: { kind: 'radarr' | 'sonarr'; step?: string }) {
  const app = APP[kind]
  const go = useGo()
  const upsert = useUpsertConnection()
  const list = useQuery(integrationsQuery)
  const id = useConnectionId()
  const c = list.data?.items.find((x) => x.id === id && x.kind === kind)
  const detail = useQuery({ ...integrationQuery(c?.id ?? 0), enabled: !!c && !step })
  const href = (key: string, at = c?.id) => `${settingsHref('integrations', 'add', kind, key)}${at ? `?id=${at}` : ''}`
  const current = addArrStep(step, !!c, c ? todoKeys(connectionChecklist(c, { detail: detail.data })) : [])
  const waiting = list.isPending || (!!c && !step && detail.isPending)
  useEffect(() => {
    if (!waiting && current.key !== step) go(href(current.key), { replace: true })
    // href is derived from the same values.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting, current.key, step, c?.id])

  const crumbs = settingsCrumbs('integrations', { label: `Add ${app}` })
  const frame = { tab: 'integrations' as const, crumbs, steps: ARR_STEPS, current: current.key, stepHref: (k: string) => href(k) }
  if (waiting || current.key !== step) return <FlowFrame {...frame} title={`Add ${app}`}><LoadingRows rows={3} /></FlowFrame>

  switch (current.key) {
    case 'connect':
      return <ConnectStep key={c?.id ?? 'new'} kind={kind} connection={c} frame={frame} nextLabel="Save and continue" onSaved={(saved) => { upsert(saved); go(href('folders', saved.id)) }} />
    case 'folders':
      return <FoldersStep connection={c!} frame={{ ...frame, back: { label: 'Connect', href: href('connect') }, skip: { label: 'Skip for now', href: href('requests') } }} nextLabel="Save folders" onSaved={(saved) => { upsert(saved); go(href('requests')) }} />
    case 'requests':
      return <RequestsStep connection={c!} inFlow frame={{ ...frame, back: { label: 'Folders', href: href('folders') }, skip: { label: 'Skip for now', href: href('webhook') } }} nextLabel="Save request target" onSaved={(saved) => { upsert(saved); go(href('webhook')) }} />
    case 'webhook':
      return <WebhookStep connection={c as Integration & { kind: typeof kind }} frame={{ ...frame, back: { label: 'Requests', href: href('requests') }, skip: { label: 'Skip for now', href: href('connected') } }} nextLabel="Connect webhook" onSaved={(saved) => { upsert(saved); go(href('connected')) }} />
    default:
      return <ArrConnected connection={c!} frame={frame} />
  }
}

function ArrConnected({ connection: c, frame }: { connection: Integration; frame: Omit<React.ComponentProps<typeof FlowFrame>, 'title'> }) {
  const app = APP[c.kind]
  const list = useQuery(integrationsQuery)
  const detail = useQuery(integrationQuery(c.id))
  const approval = useQuery(requestSettingsQuery)
  const details = detail.data?.details
  // The server places root folders with the saved mappings.
  const left = details ? unmappedRoots(details.rootFolders).map((r) => r.path) : undefined
  const profile = details?.qualityProfiles.find((q) => q.id === c.targets[0]?.qualityProfileId)?.name
  const items = list.data?.items ?? []
  const other = c.kind === 'radarr' ? 'sonarr' : 'radarr'
  const outcome = arrOutcome(c, { roots: details?.rootFolders, rootsLeft: left, profileName: profile, approval: approval.data?.approval, seerr: items.some((x) => x.kind === 'seerr') })
  return (
    <FlowFrame
      {...frame}
      finished="ok"
      title={`${app} is connected`}
      lede={[c.name, `${app} ${shortVersion(c.status.version)}`.trim()].join(' · ')}
      skip={items.some((x) => x.kind === other) ? undefined : { label: `Add ${APP[other]} →`, href: settingsHref('integrations', 'add', other) }}
      next={{ label: 'Back to Integrations', href: hubHref }}
    >
      {detail.isPending ? <LoadingRows rows={2} /> : <Outcome changed={outcome.changed} next={outcome.next} />}
    </FlowFrame>
  )
}

/** Connect Seerr: Connect → Link people → Connected. Only one Seerr can be connected. */
export function SeerrFlow({ step }: { step?: string }) {
  const go = useGo()
  const upsert = useUpsertConnection()
  const list = useQuery(integrationsQuery)
  const c = list.data?.items.find((x) => x.kind === 'seerr')
  const href = (key: string) => settingsHref('integrations', 'add', 'seerr', key)
  const current = seerrStep(step, !!c)
  useEffect(() => {
    if (!list.isPending && current.key !== step) go(href(current.key), { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.isPending, current.key, step])

  const crumbs = settingsCrumbs('integrations', { label: 'Connect Seerr' })
  const frame = { tab: 'integrations' as const, crumbs, steps: SEERR_STEPS, current: current.key, stepHref: href }
  if (list.isPending || current.key !== step) return <FlowFrame {...frame} title="Connect Seerr"><LoadingRows rows={3} /></FlowFrame>

  switch (current.key) {
    case 'connect':
      return <ConnectStep key={c?.id ?? 'new'} kind="seerr" connection={c} frame={frame} nextLabel="Save and continue" onSaved={(saved) => { upsert(saved); go(href('people')) }} />
    case 'people':
      return <PeopleStep frame={frame} skip={{ label: 'Do this later in Users', href: href('connected') }} nextLabel="Save links" onSaved={() => go(href('connected'))} />
    default:
      return <SeerrConnected connection={c!} frame={frame} />
  }
}

function SeerrConnected({ connection: c, frame }: { connection: Integration; frame: Omit<React.ComponentProps<typeof FlowFrame>, 'title'> }) {
  const users = useQuery(usersQuery)
  const seerrUsers = useQuery(seerrUsersQuery)
  const people = users.data?.items ?? []
  const known = seerrUsers.data?.items
  const linked = people.filter((p) => p.seerrUserId && (!known || known.some((s) => s.id === p.seerrUserId)))
  const unlinked = people.filter((p) => !linked.includes(p))
  const names = (list: { name: string }[]) => (list.length <= 1 ? list.map((p) => p.name).join('') : `${list.slice(0, -1).map((p) => p.name).join(', ')} and ${list[list.length - 1].name}`)
  return (
    <FlowFrame
      {...frame}
      finished="ok"
      title="Seerr is connected"
      lede={`Seerr ${shortVersion(c.status.version)}${users.data ? ` · ${linked.length} of ${people.length} people linked` : ''}`}
      next={{ label: 'Back to Integrations', href: hubHref }}
    >
      {users.isPending ? (
        <LoadingRows rows={2} />
      ) : (
        <Outcome
          changed={[
            'New requests go through Seerr.',
            'Approval is handled in Seerr. The setting here is hidden while Seerr is connected.',
            linked.length > 0 && `${names(linked)} ${linked.length === 1 ? 'requests as their Seerr user' : 'request as their Seerr users'}.`,
          ]}
          next={[
            unlinked.length > 0 && (
              <>
                {names(unlinked)} can’t request until linked. <HrefLink href={settingsHref('users')} className="underline">Link {unlinked.length === 1 ? unlinked[0].name : 'them'} in Users</HrefLink>.
              </>
            ),
            'The Inbox shows Seerr’s open requests, plus any from before today.',
          ]}
        />
      )}
    </FlowFrame>
  )
}
