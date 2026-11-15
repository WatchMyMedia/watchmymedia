import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { inboxQuery, useDecideRequest, useResolveProblem, type MediaRequest, type Problem } from '../../api/requests'
import { ConfirmDialog } from '../../components/settings/Dialogs'
import { Button, Card, Field, HrefLink, HubPage, ItemRow, LoadingRows, Message, Pill, Section, fieldInput } from '../../components/settings/Frame'
import { errorText } from '../../components/settings/hooks'
import { relativeTime } from '../../lib/format'
import { actionFailed, appSaid, decisionNote, inboxWaiting, requestApp, type RowNote } from '../../lib/inboxSettings'
import { requestStateLabel, whatLabel } from '../../lib/requests'
import { settingsCrumbs, settingsHref } from '../../lib/settingsRoutes'
import { ServerOnly } from './SettingsLayout'

const kindLabel: Record<Problem['kind'], string> = { video: 'Video', audio: 'Audio', subtitles: 'Subtitles', other: 'Other' }
const LEDE = 'Requests waiting for you, and problems people reported.'

/**
 * Settings › Server › Inbox (PLAN-ADMIN-SETTINGS.md §5.9, PLAN-ARR.md §7.2):
 * Approve, Retry and Mark resolved report in their row, failures included;
 * Decline opens a dialog for the note (boards inbox, inbox-decline).
 */
export function InboxSettings() {
  return (
    <ServerOnly tab="inbox">
      <InboxHub />
    </ServerOnly>
  )
}

function InboxHub() {
  const inbox = useQuery(inboxQuery)
  const crumbs = settingsCrumbs('inbox')
  const data = inbox.data
  const waiting = inboxWaiting(data)
  return (
    <HubPage crumbs={crumbs} title="Inbox" lede={LEDE} pill={data ? (waiting > 0 ? { tone: 'now', label: `${waiting.toLocaleString()} waiting` } : { tone: 'ok', label: 'Nothing waiting' }) : undefined}>
      {!data ? (
        inbox.isError ? <Message tone="err" title="Couldn’t load the Inbox" actions={<Button small onClick={() => void inbox.refetch()}>Try again</Button>}>{errorText(inbox.error)}</Message> : <LoadingRows rows={3} />
      ) : (
        <>
          {data.seerr ? (
            <>
              <Section title="Requests">
                <Card>
                  <ItemRow
                    title="Requests are approved in Seerr"
                    detail={data.seerr.pending === undefined ? 'Seerr didn’t answer, so its count isn’t known.' : data.seerr.pending === 0 ? 'Nothing is waiting in Seerr.' : `${data.seerr.pending.toLocaleString()} waiting in Seerr.`}
                    control={<a href={data.seerr.requestsUrl} target="_blank" rel="noreferrer" className="inline-flex min-h-[36px] items-center gap-1.5 rounded-md border border-line-2 px-3.5 text-sm hover:bg-surface-2">Open in Seerr <span aria-hidden="true">↗</span></a>}
                  />
                </Card>
              </Section>
              {data.requests.length > 0 && <RequestsSection title="Asked for before Seerr was connected" requests={data.requests} />}
            </>
          ) : (
            <RequestsSection title="Requests" requests={data.requests} />
          )}
          <ProblemsSection problems={data.problems} />
        </>
      )}
    </HubPage>
  )
}

function titleOf(r: Pick<MediaRequest, 'title' | 'year'>) {
  return `${r.title}${r.year ? ` (${r.year})` : ''}`
}

function RequestsSection({ title, requests }: { title: string; requests: MediaRequest[] }) {
  const decline = useDecideRequest()
  const [declining, setDeclining] = useState<MediaRequest | null>(null)
  const [note, setNote] = useState('')
  const [declined, setDeclined] = useState<string | null>(null)
  return (
    <Section title={title}>
      {declined && <p role="status" className="settings-fade mb-2 text-sm text-emerald-300">{declined}</p>}
      <Card>
        {requests.length === 0 && <p className="px-5 py-4 text-sm text-neutral-400">No requests are waiting.</p>}
        {requests.map((r) => <RequestRow key={r.id} r={r} onDecline={() => { decline.reset(); setNote(''); setDeclining(r) }} />)}
      </Card>
      {declining && (
        <ConfirmDialog
          title={`Decline ${titleOf(declining)}?`}
          lede={`${declining.requestedBy} sees it as Declined in Your requests, with your note.`}
          confirmLabel="Decline request"
          danger={false}
          busy={decline.isPending}
          error={decline.isError ? errorText(decline.error) : undefined}
          onClose={() => setDeclining(null)}
          onConfirm={() => {
            const r = declining
            decline.mutate({ id: r.id, approve: false, note: note.trim() || undefined }, {
              onSuccess: () => { setDeclining(null); setDeclined(`✓ Declined ${titleOf(r)}. ${r.requestedBy} has been told.`) },
            })
          }}
        >
          <div className="mt-4">
            <Field label={`Note for ${declining.requestedBy}`} hint="Optional. Up to 500 characters.">
              {(p) => <textarea {...p} value={note} maxLength={500} rows={3} placeholder="Why, or what to ask for instead" onChange={(e) => setNote(e.target.value)} className={`${fieldInput} py-2`} />}
            </Field>
          </div>
        </ConfirmDialog>
      )}
    </Section>
  )
}

function RequestRow({ r, onDecline }: { r: MediaRequest; onDecline: () => void }) {
  const decide = useDecideRequest()
  const [note, setNote] = useState<RowNote | null>(null)
  const waiting = r.status === 'pending' || r.status === 'failed'
  const approve = () => {
    setNote(null)
    decide.mutate({ id: r.id, approve: true }, {
      onSuccess: (reply) => setNote(decisionNote(reply)),
      onError: (error) => { const f = actionFailed('approve', error, errorText); setNote({ tone: 'err', text: `${f.title}. ${f.body}` }) },
    })
  }
  const state = requestStateLabel(r)
  const detail = note ? undefined
    : r.status === 'pending' ? `Asked for by ${r.requestedBy} · ${relativeTime(r.createdAt)}`
    : r.status === 'failed' ? <>{appSaid(r)} Fix it in {requestApp(r)}, or <HrefLink href={settingsHref('integrations')} className="underline">change where requests go</HrefLink>, then retry.</>
    : `${state.label} · asked for by ${r.requestedBy} · ${relativeTime(r.createdAt)}`
  const href = r.movieId ? `/movies/${r.movieId}` : r.showId ? `/shows/${r.showId}` : undefined
  return (
    <ItemRow
      title={href ? <HrefLink href={href} className="hover:underline">{titleOf(r)}</HrefLink> : titleOf(r)}
      pill={<>
        <Pill tone="off">{whatLabel(r)}</Pill>
        {r.status === 'failed' && <Pill tone="err">Failed</Pill>}
        {r.status === 'approved' && <Pill tone="ok">Approved</Pill>}
        {r.status === 'available' && <Pill tone="ok">In your library</Pill>}
      </>}
      detail={detail}
      control={waiting ? <>
        <Button small onClick={onDecline} disabled={decide.isPending}>Decline…</Button>
        <Button small variant="primary" busy={decide.isPending} busyLabel={r.status === 'failed' ? 'Retrying…' : 'Approving…'} onClick={approve}>{r.status === 'failed' ? 'Retry' : 'Approve'}</Button>
      </> : undefined}
    >
      {note && <p role={note.tone === 'err' ? 'alert' : 'status'} className={`settings-fade mt-0.5 text-sm ${note.tone === 'err' ? 'text-red-300' : 'text-emerald-300'}`}>{note.text}</p>}
    </ItemRow>
  )
}

/** Mark resolved lives here, not in the row: a resolved problem leaves the list, and its result stays to read. */
function ProblemsSection({ problems }: { problems: Problem[] }) {
  const resolve = useResolveProblem()
  const [done, setDone] = useState<string | null>(null)
  const [failed, setFailed] = useState<{ id: number; title: string; body: string } | null>(null)
  const mark = (p: Problem) => {
    setDone(null)
    setFailed(null)
    resolve.mutate(p.id, {
      onSuccess: () => setDone(`✓ Marked ${p.title} resolved. ${p.reportedBy} sees it as fixed.`),
      onError: (error) => setFailed({ id: p.id, ...actionFailed('resolve', error, errorText) }),
    })
  }
  return (
    <Section title="Problems">
      {done && <p role="status" className="settings-fade mb-2 text-sm text-emerald-300">{done}</p>}
      <Card>
        {problems.length === 0 && <p className="px-5 py-4 text-sm text-neutral-400">No problems reported.</p>}
        {problems.map((p) => (
          <ItemRow
            key={p.id}
            title={<ProblemTitle p={p} />}
            pill={<Pill tone="off">{kindLabel[p.kind]}</Pill>}
            detail={`${p.note ? `“${p.note}” · ` : ''}${p.reportedBy} · ${relativeTime(p.createdAt)}`}
            control={<Button small busy={resolve.isPending && resolve.variables === p.id} busyLabel="Marking…" disabled={resolve.isPending} onClick={() => mark(p)}>Mark resolved</Button>}
          >
            {p.details && <p className="mt-0.5 text-xs text-neutral-500">{p.details}</p>}
            {failed?.id === p.id && <Message tone="err" focus className="mt-2" title={failed.title}>{failed.body}</Message>}
          </ItemRow>
        ))}
      </Card>
    </Section>
  )
}

function ProblemTitle({ p }: { p: Problem }) {
  const href = p.itemType === 'movie' ? `/movies/${p.itemId}` : p.showId ? `/shows/${p.showId}/seasons/${p.season ?? 1}/episodes/${p.itemId}` : undefined
  return href ? <HrefLink href={href} className="hover:underline">{p.title}</HrefLink> : <>{p.title}</>
}
