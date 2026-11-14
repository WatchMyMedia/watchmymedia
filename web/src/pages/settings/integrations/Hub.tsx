import { useEffect, useRef, useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { integrationQuery, integrationsQuery, type Integration } from '../../../api/integrations'
import { usersQuery } from '../../../api/queries'
import { requestSettingsQuery, useUpdateRequestSettings } from '../../../api/requests'
import { Button, Card, Choice, HrefLink, HubPage, ItemRow, LoadingRows, Message, Pill, SaveNote, Section } from '../../../components/settings/Frame'
import { errorText, useGo, useInstantSave } from '../../../components/settings/hooks'
import { relativeTime } from '../../../lib/format'
import { connectionChecklist, connectionPill, hubBanners, hubLede, type CheckItem } from '../../../lib/integrationsSettings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { FlashBanner } from './parts'
import { hubHref, pageHref } from './shared'

const addHref = (app: 'radarr' | 'sonarr' | 'seerr') => settingsHref('integrations', 'add', app)

const APPS = [
  { app: 'radarr', eyebrow: 'Movies', name: 'Radarr', text: 'Requests for movies, missing-movie searches and instant scans after an import. Needs Radarr 5 or later.', add: 'Add Radarr' },
  { app: 'sonarr', eyebrow: 'TV', name: 'Sonarr', text: 'Requests for shows and seasons, missing episodes and instant scans. Needs Sonarr 4.0.6 or later.', add: 'Add Sonarr' },
  { app: 'seerr', eyebrow: 'Requests', name: 'Seerr', text: 'Optional. Lets Seerr take and approve requests instead. Needs Seerr 3 or later. Overseerr and Jellyseerr aren’t supported.', add: 'Connect Seerr' },
] as const

/**
 * The Integrations hub (§5.2): a card per app with what it is for when
 * nothing is connected; then a row per connection with its checklist, a
 * banner for one that stopped answering, and who needs approval, which
 * saves at once. Drawn inside ServerOnly, so members never see Add.
 */
export function IntegrationsHub() {
  const go = useGo()
  const list = useQuery(integrationsQuery)
  const items = list.data?.items ?? []
  const arrs = items.filter((c) => c.kind !== 'seerr')
  const seerr = items.find((c) => c.kind === 'seerr')
  const details = useQueries({ queries: arrs.map((c) => integrationQuery(c.id)) })
  const users = useQuery({ ...usersQuery, enabled: !!seerr })
  const people = users.data ? { linked: users.data.items.filter((u) => u.seerrUserId).length, total: users.data.items.length } : undefined
  const crumbs = settingsCrumbs('integrations')

  if (list.isPending) return <HubPage crumbs={crumbs} title="Integrations"><LoadingRows rows={3} /></HubPage>
  if (list.isError) {
    return (
      <HubPage crumbs={crumbs} title="Integrations">
        <Message tone="err" title="Couldn’t read the connections" actions={<Button small onClick={() => void list.refetch()}>Try again</Button>}>{errorText(list.error)}</Message>
      </HubPage>
    )
  }

  if (!items.length) {
    return (
      <HubPage crumbs={crumbs} title="Integrations" lede={hubLede([])} banner={<FlashBanner href={hubHref} />}>
        <div className="grid gap-4 md:grid-cols-3">
          {APPS.map((a) => (
            <div key={a.app} className="flex flex-col rounded-xl border border-line bg-surface p-5">
              <span className="text-xs font-semibold tracking-wider text-neutral-400 uppercase">{a.eyebrow}</span>
              <h3 className="mt-1 text-base font-semibold">{a.name}</h3>
              <p className="mt-1 flex-1 text-[13.5px] text-neutral-400">{a.text}</p>
              <div className="mt-4"><Button small guarded onClick={() => go(addHref(a.app))}>{a.add}</Button></div>
            </div>
          ))}
        </div>
      </HubPage>
    )
  }

  const banners = hubBanners(items)
  return (
    <HubPage
      crumbs={crumbs}
      title="Integrations"
      lede={hubLede(items)}
      actions={<AddMenu seerr={!!seerr} />}
      banner={
        <>
          <FlashBanner href={hubHref} />
          {banners.map((b) => (
            <Message key={b.id} tone="err" title={b.title}>
              <p>
                {b.body.replace(/change the connection\.$/, '')}
                <HrefLink href={pageHref(b.id, 'connection')}>change the connection</HrefLink>.
              </p>
              {b.said && <p className="mt-1 text-[12.5px] text-neutral-400">Last check: {b.said}</p>}
            </Message>
          ))}
        </>
      }
    >
      <Card className="overflow-hidden">
        {items.map((c) => {
          const at = arrs.indexOf(c)
          const checklist = connectionChecklist(c, { detail: at >= 0 ? details[at]?.data : undefined, people: c.kind === 'seerr' ? people : undefined })
          return <ConnectionRow key={c.id} c={c} checklist={checklist} />
        })}
      </Card>

      <Section title="Requests">
        {seerr ? (
          <Card padded>
            <h4 className="font-semibold">Who needs approval</h4>
            <p className="mt-1 text-sm text-neutral-400">Requests go to Seerr. Approval, quotas and notifications happen there.</p>
          </Card>
        ) : (
          <Approval />
        )}
      </Section>

      {!seerr && (
        <Section title="Optional">
          <Card>
            <ItemRow title="Seerr" detail="Let Seerr take and approve requests instead." control={<Button small guarded onClick={() => go(addHref('seerr'))}>Connect Seerr</Button>} />
          </Card>
        </Section>
      )}
    </HubPage>
  )
}

/** A connection's row: the whole row opens its page; anything to do is a link straight to that step. */
function ConnectionRow({ c, checklist }: { c: Integration; checklist: CheckItem[] }) {
  const pill = connectionPill(c, checklist)
  return (
    <div className="relative flex min-h-[60px] items-center gap-4 px-5 py-3.5 focus-within:bg-surface-2 hover:bg-surface-2">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <h4 className="min-w-0 font-medium break-words">
            <HrefLink href={pageHref(c.id)} className="outline-none before:absolute before:inset-0 before:content-['']">{c.name}</HrefLink>
          </h4>
          <Pill tone={pill.tone}>{pill.label}</Pill>
        </div>
        <p className="mt-0.5 text-sm text-neutral-400">
          {checklist.map((item, i) => (
            <span key={item.key}>
              {i > 0 && ' · '}
              {item.state === 'done' ? (
                <>✓ {item.label}</>
              ) : item.state === 'todo' ? (
                <HrefLink href={pageHref(c.id, item.key)} className="relative z-10 text-warn underline underline-offset-2">{item.todo}</HrefLink>
              ) : (
                item.label
              )}
            </span>
          ))}
          {c.status.checkedAt && <> · checked {relativeTime(c.status.checkedAt)}</>}
        </p>
      </div>
      <span aria-hidden="true" className="text-lg text-neutral-500">›</span>
    </div>
  )
}

/** Who needs approval: a choice that saves at once and says what it did (§3.6). */
function Approval() {
  const settings = useQuery(requestSettingsQuery)
  const update = useUpdateRequestSettings()
  const save = useInstantSave()
  // The choice shows as made while it saves and the server's value is read again.
  const [pending, setPending] = useState<'admins' | 'everyone' | null>(null)
  const [last, setLast] = useState<'admins' | 'everyone'>('admins')
  if (settings.isPending) return <LoadingRows rows={1} />
  if (settings.isError) {
    return <Message tone="err" title="Couldn’t read who needs approval" actions={<Button small onClick={() => void settings.refetch()}>Try again</Button>}>{errorText(settings.error)}</Message>
  }
  const value = pending ?? settings.data.approval
  const choose = (v: 'admins' | 'everyone') => {
    if (v === value) return
    setPending(v)
    void save
      .run(async () => {
        await update.mutateAsync(v)
        await settings.refetch()
      })
      .then((ok) => {
        if (ok) setLast(v)
        setPending(null)
      })
  }
  const saved = save.state.status === 'saved'
  return (
    <Card padded>
      <fieldset>
        <legend className="font-semibold">Who needs approval</legend>
        <div className="mt-3">
          <Choice name="request-approval" value="admins" checked={value === 'admins'} disabled={save.state.status === 'saving'} onChange={() => choose('admins')} title="Everyone but admins">
            Admins’ requests go straight to Radarr or Sonarr. Everyone else’s wait in the Inbox.
          </Choice>
          <Choice name="request-approval" value="everyone" checked={value === 'everyone'} disabled={save.state.status === 'saving'} onChange={() => choose('everyone')} title="No one">
            Every request goes straight to the app.
          </Choice>
        </div>
      </fieldset>
      <div className="mt-3 min-h-5">
        {saved ? (
          <span role="status" className="settings-fade text-sm text-emerald-300">
            ✓ Saved.{' '}
            {last === 'everyone' ? (
              <>Requests already waiting in the <HrefLink href={settingsHref('inbox')} className="underline">Inbox</HrefLink> still wait there.</>
            ) : (
              <>From now on, requests from people who aren’t admins wait in the <HrefLink href={settingsHref('inbox')} className="underline">Inbox</HrefLink>.</>
            )}
          </span>
        ) : (
          <SaveNote state={save.state} />
        )}
      </div>
    </Card>
  )
}

/** Add…: Radarr and Sonarr can be added more than once; Seerr only while none is connected. */
function AddMenu({ seerr }: { seerr: boolean }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', key)
    return () => { document.removeEventListener('mousedown', away); document.removeEventListener('keydown', key) }
  }, [open])
  const links = [
    { href: addHref('radarr'), label: 'Add Radarr' },
    { href: addHref('sonarr'), label: 'Add Sonarr' },
    ...(seerr ? [] : [{ href: addHref('seerr'), label: 'Connect Seerr' }]),
  ]
  return (
    <div ref={box} className="relative">
      <Button small guarded aria-expanded={open} aria-haspopup="true" onClick={() => setOpen(!open)}>Add…</Button>
      {open && (
        <ul className="settings-fade absolute right-0 z-30 mt-1.5 min-w-44 rounded-lg border border-line-2 bg-surface py-1 shadow-xl">
          {links.map((l) => (
            <li key={l.href}>
              <HrefLink href={l.href} className="flex min-h-[44px] items-center px-4 text-sm hover:bg-surface-2 focus-visible:bg-surface-2">{l.label}</HrefLink>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
