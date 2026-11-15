import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { integrationQuery, integrationsQuery, useDeleteIntegration, type Integration } from '../../../api/integrations'
import { usersQuery } from '../../../api/queries'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { Button, Card, HrefLink, HubPage, ItemRow, LoadingRows, Message, Pill, RemoveSection } from '../../../components/settings/Frame'
import { errorText, useGo } from '../../../components/settings/hooks'
import { relativeTime } from '../../../lib/format'
import { clock } from '../../../lib/integrations'
import { APP, connectionChecklist, hubBanners, removeWording, shortVersion, things, todoCard, todoKeys } from '../../../lib/integrationsSettings'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { FlashBanner } from './parts'
import { hubHref, pageHref, setFlash } from './shared'

/** One connection's page (§5.2): its checklist as rows that each open their step by itself, and Remove apart at the bottom. */
export function ConnectionPage({ id }: { id: number }) {
  const list = useQuery(integrationsQuery)
  const c = list.data?.items.find((x) => x.id === id)
  if (list.isPending) {
    return <HubPage crumbs={settingsCrumbs('integrations', { label: 'Connection' })} title="Connection"><LoadingRows rows={4} /></HubPage>
  }
  if (!c) {
    return (
      <HubPage crumbs={settingsCrumbs('integrations', { label: 'Not found' })} title="This connection isn’t here any more">
        <Message tone="info" actions={<HrefLink href={hubHref} className="text-sm underline">Back to Integrations</HrefLink>}>
          {list.isError ? errorText(list.error) : 'It may have been removed in another tab.'}
        </Message>
      </HubPage>
    )
  }
  return <Page key={c.id} c={c} />
}

function Page({ c }: { c: Integration }) {
  const app = APP[c.kind]
  const t = things(c.kind)
  const go = useGo()
  const page = pageHref(c.id)
  const arr = c.kind !== 'seerr'
  const detail = useQuery({ ...integrationQuery(c.id), enabled: arr })
  const users = useQuery({ ...usersQuery, enabled: !arr })
  const people = users.data ? { linked: users.data.items.filter((u) => u.seerrUserId).length, total: users.data.items.length } : undefined
  const items = connectionChecklist(c, { detail: detail.data, people })
  const todo = todoKeys(items)
  const card = todo.length ? todoCard(c, todo[0]) : null
  const down = hubBanners([c])[0]
  const state = (key: string) => items.find((i) => i.key === key)?.state
  const details = detail.data?.details
  const profile = details?.qualityProfiles.find((q) => q.id === c.targets[0]?.qualityProfileId)?.name
  const hooked = !!(c.status.webhook.lastTestAt || c.status.webhook.lastEventAt)
  const [removing, setRemoving] = useState(false)

  return (
    <HubPage
      crumbs={settingsCrumbs('integrations', { label: c.name })}
      title={c.name}
      lede={<span className="[overflow-wrap:anywhere]">{[`${app} ${shortVersion(c.status.version)}`.trim(), c.url, c.status.checkedAt && `checked ${relativeTime(c.status.checkedAt)}`].filter(Boolean).join(' · ')}</span>}
      pill={down ? { tone: 'err', label: 'Not answering' } : todo.length ? { tone: 'warn', label: `${todo.length} to do` } : { tone: 'ok', label: 'Connected' }}
      banner={
        <>
          <FlashBanner href={page} />
          {down && (
            <Message tone="err" title={down.title}>
              <p>
                {down.body.replace(/change the connection\.$/, '')}
                <HrefLink href={pageHref(c.id, 'connection')}>change the connection</HrefLink>.
              </p>
              {down.said && <p className="mt-1 text-[12.5px] text-neutral-400">Last check: {down.said}</p>}
            </Message>
          )}
        </>
      }
    >
      {card && (
        <section aria-label="To do" className="rounded-xl border border-accent bg-accent/[0.06] p-5">
          <div className="text-xs font-bold tracking-wider text-accent uppercase">To do</div>
          <h3 className="mt-1 text-base font-semibold">{card.title}</h3>
          <p className="mt-1 text-sm text-neutral-400">{card.body}</p>
          <div className="mt-3">
            <Button variant="primary" guarded={todo[0] !== 'people'} onClick={() => go(pageHref(c.id, todo[0]))}>{card.action}</Button>
          </div>
        </section>
      )}

      <Card>
        <ItemRow
          title="Connection"
          detail={arr ? 'Name, address and API key.' : 'Address and API key.'}
          control={<Button small guarded onClick={() => go(pageHref(c.id, 'connection'))}>Change</Button>}
        />
        {arr && (
          <>
            <ItemRow
              title="Folders"
              pill={c.pathMap.length ? <Pill tone="ok">{c.pathMap.length} mapped</Pill> : state('folders') === 'done' ? <Pill tone="ok">Same folders</Pill> : state('folders') === 'todo' ? <Pill tone="warn">Not mapped</Pill> : null}
              detail={
                c.pathMap.length
                  ? c.pathMap.map((m) => `${m.from} → ${m.to}`).join(' · ')
                  : state('folders') === 'done' ? `${app} and this server see the same folders.` : `Line up ${app}’s folders with this server’s, so imports match exactly.`
              }
              control={<Button small guarded onClick={() => go(pageHref(c.id, 'folders'))}>Change</Button>}
            >
              {c.status.webhook.outside > 0 && (
                <p className="mt-1 text-[13px] text-warn">{c.status.webhook.outside === 1 ? '1 event was' : `${c.status.webhook.outside} events were`} for folders outside your libraries. Check the mappings.</p>
              )}
              {detail.data?.detailsError && <p className="mt-1 text-[13px] text-warn">Couldn’t read {app}’s root folders: {detail.data.detailsError}</p>}
            </ItemRow>
            <ItemRow
              title="Requests"
              pill={c.targets[0] ? <Pill tone="ok">On</Pill> : <Pill tone="warn">Off</Pill>}
              detail={c.targets[0] ? `New ${t.many} go to ${c.targets[0].rootFolder}${profile ? ` at ${profile}` : ''}.` : `People can’t request ${t.many} yet.`}
              control={<Button small guarded onClick={() => go(pageHref(c.id, 'requests'))}>{c.targets[0] ? 'Change' : 'Choose'}</Button>}
            />
            <ItemRow
              title="Webhook"
              pill={hooked ? <Pill tone="ok">Connected</Pill> : <Pill tone="warn">Not connected</Pill>}
              detail={
                c.status.webhook.lastEventAt ? `Last event ${relativeTime(c.status.webhook.lastEventAt)}.`
                  : c.status.webhook.lastTestAt ? `Test event received at ${clock(c.status.webhook.lastTestAt)}.`
                    : `${app} doesn’t tell this server about imports yet.`
              }
              control={<Button small guarded onClick={() => go(pageHref(c.id, 'webhook'))}>{hooked ? 'Change' : 'Connect'}</Button>}
            />
          </>
        )}
        {!arr && (
          <ItemRow
            title="People"
            pill={people ? (people.linked >= people.total ? <Pill tone="ok">All linked</Pill> : <Pill tone="warn">{people.total - people.linked} not linked</Pill>) : null}
            detail={people ? `${people.linked} of ${people.total} people linked. People without a Seerr user can’t request.` : 'Who each person requests as in Seerr.'}
            control={<Button small onClick={() => go(pageHref(c.id, 'people'))}>Change</Button>}
          />
        )}
      </Card>

      <RemoveSection>
        <ItemRow
          title="Remove this connection"
          detail={`${app} itself isn’t changed.`}
          control={<Button small variant="danger" guarded onClick={() => setRemoving(true)}>Remove…</Button>}
        />
      </RemoveSection>

      {removing && <RemoveDialog c={c} onClose={() => setRemoving(false)} />}
    </HubPage>
  )
}

/** Remove, worded per app (§3.8), in place of the browser's confirm. */
function RemoveDialog({ c, onClose }: { c: Integration; onClose: () => void }) {
  const go = useGo()
  const remove = useDeleteIntegration()
  const w = removeWording(c)
  return (
    <ConfirmDialog
      title={w.title}
      stays={w.stays}
      stops={w.stops}
      confirmLabel="Remove"
      guarded
      busy={remove.isPending}
      error={remove.isError && visibleConfirmationError(remove.error) ? `${errorText(remove.error)} The connection is still here.` : undefined}
      onClose={onClose}
      onConfirm={() =>
        remove.mutate(c.id, {
          onSuccess: () => {
            setFlash({ href: hubHref, ...w.done })
            go(hubHref)
          },
        })
      }
    />
  )
}
