import { useEffect, useRef, useState, type CSSProperties, type FocusEvent, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { inboxQuery, myRequestsQuery, requestOptionsQuery, useCancelRequest, useDecideRequest, useResolveProblem, type MediaRequest, type Problem } from '../../../src/api/requests'
import { actionFailed, decisionNote, type RowNote } from '../../../src/lib/inboxSettings'
import { canCancel, requestStateLabel } from '../../../src/lib/requests'
import { useFocus, useMediaUrls, useNav, useSession } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { Button, KeyHints } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { SidePanel } from '../../ui/Overlays'
import { SkeletonText } from '../../ui/States'
import { TextField } from '../../ui/TextField'
import { VScroll } from '../../ui/VScroll'
import { problemWords } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'
import { InfoPanel, PanelQr } from '../settings/Panels'
import { askedLine, decidable, inboxCounts, mineCount, reportedLine, requestSub, seerrUrl, statusLook } from './requestRows'
import './requests.css'

const words = (e: unknown) => problemWords(e).title

function Poster({ title, path, wide }: { title: string; path?: string; wide?: boolean }) {
  const media = useMediaUrls()
  const tone = placeholderTone(title)
  const src = media.imageUrl(path, 'w154')
  return <span className={`req__poster${wide ? ' req__poster--wide' : ''}`} style={{ '--ph-a': tone[0], '--ph-b': tone[1] } as CSSProperties} aria-hidden="true">
    {src && <img src={src} alt="" decoding="async" loading="lazy"/>}
  </span>
}

/** Where a request stands: its icon and words in its colour, and the download's bar. */
function Status({ r }: { r: MediaRequest }) {
  const look = statusLook(r)
  return <span className="req__state">
    <span className={`req__status req__status--${look.tone}`}><Icon name={look.icon}/>{requestStateLabel(r).label}</span>
    {look.progress !== undefined && <span className="req__bar"><span style={{ width: `${Math.round(look.progress * 100)}%` }}/></span>}
  </span>
}

function Note({ note }: { note?: RowNote }) {
  return note ? <span role="status" className={`req__note req__note--${note.tone}`}>{note.text}</span> : null
}

/**
 * Your requests and, for admins, the Inbox (Requests on the canvas, Tweak
 * `view`: `list`): each request with where it stands and Cancel request on
 * one still waiting; in the Inbox, requests to Approve or Decline (with a
 * note typed on the TV's keyboard), failed ones to Retry, problems to Mark
 * resolved, and Seerr's requests page as a QR code.
 */
export function RequestsScreen({ route }: ScreenProps<'requests'>) {
  const session = useSession()
  const nav = useNav()
  const options = useQuery(requestOptionsQuery)
  const admin = session.isAdmin
  const inbox = admin && !!route.inbox
  const [hint, setHint] = useState<string>()
  const title = inbox ? 'Inbox' : 'Your requests'
  const onFocus = (e: FocusEvent) => setHint((e.target as HTMLElement).closest<HTMLElement>('[data-hint]')?.dataset.hint)
  const count = options.data?.inbox ?? 0
  return <div className="requests">
    <header className="requests-head">
      <p className="requests-head__crumb">Settings › {title}</p>
      <h1 className="requests-head__title">{title}</h1>
      <Sub inbox={inbox}/>
    </header>
    {admin && <nav className="requests-tabs" aria-label="Requests" onFocus={() => setHint(undefined)}>
      <button type="button" role="tab" aria-selected={!inbox} data-focus="tab/mine" data-autofocus={!inbox || undefined} className="requests-tab"
        onClick={() => { if (inbox) nav.replace({ name: 'requests' }) }}>Your requests</button>
      <button type="button" role="tab" aria-selected={inbox} data-focus="tab/inbox" data-autofocus={inbox || undefined} className="requests-tab"
        onClick={() => { if (!inbox) nav.replace({ name: 'requests', inbox: true }) }}>Inbox{count > 0 && <span className="requests-tab__count">{count}</span>}</button>
    </nav>}
    <div className="requests-list" onFocus={onFocus}>
      {inbox ? <InboxList/> : <MineList first={!admin}/>}
    </div>
    <div className="requests-hints-host">
      <KeyHints hints={[...(hint ? [{ key: 'OK', label: hint }] : []), { key: '▲ ▼', label: inbox ? 'Other requests and problems' : 'Other requests' }, { key: 'Return', label: 'Back' }]}/>
    </div>
  </div>
}

function Sub({ inbox }: { inbox: boolean }) {
  const mine = useQuery({ ...myRequestsQuery, enabled: !inbox })
  const box = useQuery({ ...inboxQuery, enabled: inbox })
  const text = inbox ? box.data && inboxCounts(box.data) : mine.data && mineCount(mine.data.items.length)
  return <p className="requests-head__sub">{text ?? <SkeletonText width="14rem"/>}</p>
}

function Empty({ title, children }: { title: string; children: ReactNode }) {
  return <div className="requests-empty"><h2>{title}</h2><p className="tv-note">{children}</p></div>
}

function Failed({ title, error, onRetry, autoFocus }: { title: string; error: unknown; onRetry(): void; autoFocus: boolean }) {
  return <div className="requests-empty" role="alert">
    <h2>{title}</h2><p className="tv-note">{problemWords(error).body}</p>
    <div className="tv-actions"><Button id="requests/retry" variant="primary" autoFocus={autoFocus} onPress={onRetry}>Try again</Button></div>
  </div>
}

/** The rows' focus ids, to move focus on when one goes. */
const mineFocus = (r: MediaRequest, cancellable: boolean) => cancellable ? `request/${r.id}/cancel` : `request/${r.id}`

function MineList({ first }: { first: boolean }) {
  const nav = useNav()
  const focus = useFocus()
  const options = useQuery(requestOptionsQuery)
  const mine = useQuery(myRequestsQuery)
  const cancel = useCancelRequest()
  const [notes, setNotes] = useState<Record<number, RowNote>>({})
  const backend = options.data?.backend
  if (mine.isPending) return <SkeletonText width="30rem"/>
  if (mine.isError) return <Failed title="Couldn’t load your requests" error={mine.error} onRetry={() => void mine.refetch()} autoFocus={first}/>
  const items = mine.data.items
  if (!items.length) {
    return <Empty title="No requests yet">{backend === 'seerr' ? 'Requests go to Seerr. Approval, quotas and notifications happen there.' : 'Ask for a movie or a show from Search, or a missing season from its show.'}</Empty>
  }
  const doCancel = (r: MediaRequest, at: number) => {
    const next = items[at + 1] ?? items[at - 1]
    cancel.mutate(r.id, {
      onSuccess: () => requestAnimationFrame(() => {
        if (next && !focus.focusId(mineFocus(next, canCancel(next, backend)))) focus.focusId(`request/${next.id}`)
      }),
      onError: (e) => setNotes((n) => ({ ...n, [r.id]: { tone: 'err', text: `Couldn’t cancel it. ${problemWords(e).body}` } })),
    })
  }
  return <VScroll>
    {backend === 'seerr' && <p className="tv-note requests-seerr">Requests go to Seerr. Approval, quotas and notifications happen there.</p>}
    {items.map((r, i) => {
      const cancellable = canCancel(r, backend)
      const busy = cancel.isPending && cancel.variables === r.id
      const body = <>
        <Poster title={r.title} path={r.posterPath}/>
        <span className="req__text"><span className="req__title">{r.title}</span><span className="req__sub">{requestSub(r)}</span><Note note={notes[r.id]}/></span>
        <Status r={r}/>
        <span className="req__actions req__actions--cancel">
          {cancellable && <Button id={mineFocus(r, true)} autoFocus={first && i === 0} busy={busy} onPress={() => doCancel(r, i)}>{busy ? 'Cancelling…' : 'Cancel request'}</Button>}
        </span>
      </>
      if (cancellable) return <div key={r.id} className="req" data-vitem data-hint="Cancel this request">{body}</div>
      const open = r.status === 'available' ? r.showId ? () => nav.push({ name: 'show', id: r.showId! }) : r.movieId ? () => nav.push({ name: 'movie', id: r.movieId! }) : undefined : undefined
      return <button key={r.id} type="button" className="req" data-focus={mineFocus(r, false)} data-autofocus={(first && i === 0) || undefined}
        data-hint={open ? 'Open it' : undefined} onClick={open}>{body}</button>
    })}
  </VScroll>
}

function InboxList() {
  const box = useQuery(inboxQuery)
  const decide = useDecideRequest()
  const resolve = useResolveProblem()
  const [notes, setNotes] = useState<Record<string, RowNote>>({})
  const [declining, setDeclining] = useState<MediaRequest>()
  const [seerr, setSeerr] = useState(false)
  const focus = useFocus()
  /** Where focus goes when a decision takes away what had it: the same row as it now is, else the next, else the one before. */
  const after = useRef<string[]>([])
  useEffect(() => {
    const active = document.activeElement
    if (!after.current.length || (active && active !== document.body && active.isConnected)) return
    for (const id of after.current) if (focus.focusId(id)) break
    after.current = []
  }, [box.data, focus])
  const note = (key: string, n: RowNote) => setNotes((all) => ({ ...all, [key]: n }))
  if (box.isPending) return <SkeletonText width="30rem"/>
  if (box.isError) return <Failed title="Couldn’t load the Inbox" error={box.error} onRetry={() => void box.refetch()} autoFocus={false}/>
  const { requests, problems } = box.data
  const seerrInbox = box.data.seerr
  const seerrLink = seerrUrl(seerrInbox?.requestsUrl)
  if (!requests.length && !problems.length && !seerrInbox) {
    return <Empty title="Nothing in the Inbox">Requests that need your approval and problems people report show up here.</Empty>
  }
  const rows = [...requests.map((r) => ({ key: `r${r.id}`, ids: [`inbox/r${r.id}/approve`, `inbox/r${r.id}`] })), ...problems.map((p) => ({ key: `p${p.id}`, ids: [`inbox/p${p.id}/resolve`] }))]
  const aim = (key: string) => {
    const at = rows.findIndex((r) => r.key === key)
    after.current = [rows[at], rows[at + 1], rows[at - 1]].flatMap((r) => r?.ids ?? [])
  }
  const approve = (r: MediaRequest) => {
    aim(`r${r.id}`)
    decide.mutate({ id: r.id, approve: true }, {
      onSuccess: (reply) => note(`r${r.id}`, decisionNote(reply)),
      onError: (e) => { const f = actionFailed('approve', e, words); note(`r${r.id}`, { tone: 'err', text: `${f.title}. ${f.body}` }) },
    })
  }
  const decline = (r: MediaRequest, text: string) => {
    aim(`r${r.id}`)
    decide.mutate({ id: r.id, approve: false, note: text.trim().slice(0, 500) || undefined }, {
      onSuccess: (reply) => note(`r${r.id}`, decisionNote(reply)),
      onError: (e) => note(`r${r.id}`, { tone: 'err', text: `Couldn’t decline it, so it’s still waiting. ${problemWords(e).body}` }),
    })
  }
  const markResolved = (p: Problem) => {
    aim(`p${p.id}`)
    resolve.mutate(p.id, {
      onSuccess: () => note(`p${p.id}`, { tone: 'ok', text: `✓ Marked resolved. ${p.reportedBy} sees it as fixed.` }),
      onError: (e) => { const f = actionFailed('resolve', e, words); note(`p${p.id}`, { tone: 'err', text: `${f.title}. ${f.body}` }) },
    })
  }
  const busyRequest = (r: MediaRequest) => decide.isPending && decide.variables?.id === r.id
  return <>
    <VScroll>
      {seerrInbox && <div className="req" data-vitem data-hint={seerrLink ? 'Show Seerr’s address' : undefined}>
        <span className="req__text">
          <span className="req__title">Requests are approved in Seerr</span>
          <span className="req__sub">{[seerrInbox.pending !== undefined && `${seerrInbox.pending} waiting in Seerr`, requests.length > 0 && 'Below: asked for before Seerr was connected'].filter(Boolean).join(' · ')}</span>
        </span>
        {seerrLink && <span className="req__actions"><Button id="inbox/seerr" onPress={() => setSeerr(true)}>Open Seerr</Button></span>}
      </div>}
      {requests.map((r) => {
        const line = askedLine(r)
        const can = decidable(r)
        const busy = busyRequest(r)
        const body = <>
          <Poster title={r.title} path={r.posterPath}/>
          <span className="req__text">
            <span className="req__line"><b>{line.who}</b> asked for {line.what && <><b>{line.what}</b> of </>}<i>{line.title}</i> <small>· {line.ago}</small></span>
            {r.status !== 'pending' && <Status r={r}/>}
            <Note note={notes[`r${r.id}`]}/>
          </span>
          {can && <span className="req__actions">
            <Button id={`inbox/r${r.id}/approve`} variant="primary" busy={busy} onPress={() => approve(r)}>{busy ? 'Sending…' : r.status === 'failed' ? 'Retry' : 'Approve'}</Button>
            <Button id={`inbox/r${r.id}/decline`} busy={busy} onPress={() => setDeclining(r)}>Decline</Button>
          </span>}
        </>
        return can ? <div key={`r${r.id}`} className="req" data-vitem data-hint={r.status === 'failed' ? 'Send it again, or decline it' : 'Approve or decline it'}>{body}</div>
          : <button key={`r${r.id}`} type="button" className="req" data-focus={`inbox/r${r.id}`}>{body}</button>
      })}
      {problems.map((p) => {
        const line = reportedLine(p)
        const busy = resolve.isPending && resolve.variables === p.id
        return <div key={`p${p.id}`} className="req" data-vitem data-hint="Mark it resolved">
          <Poster title={p.title} wide={p.itemType === 'episode'}/>
          <span className="req__text">
            <span className="req__line"><b>{line.who}</b> reported {line.what} on <i>{line.title}</i> <small>· {line.ago}</small></span>
            {p.note && <span className="req__quote">“{p.note}”</span>}
            {p.details && <span className="req__details">{p.details}</span>}
            <Note note={notes[`p${p.id}`]}/>
          </span>
          <span className="req__actions"><Button id={`inbox/p${p.id}/resolve`} busy={busy} onPress={() => markResolved(p)}>{busy ? 'Resolving…' : 'Mark resolved'}</Button></span>
        </div>
      })}
    </VScroll>
    {declining && <DeclinePanel request={declining} onClose={() => setDeclining(undefined)} onDecline={(text) => { decline(declining, text); setDeclining(undefined) }}/>}
    {seerr && seerrLink && <InfoPanel title="Open Seerr" onClose={() => setSeerr(false)}>
      <p className="settings-panel__body">Approving and declining happen in Seerr. Scan the code with your phone, or open this address on a phone or computer.</p>
      <PanelQr url={seerrLink} label="QR code that opens Seerr’s requests"/>
    </InfoPanel>}
  </>
}

/** Decline, with a note for the person who asked, typed with the TV's keyboard; it may be left empty. */
function DeclinePanel({ request, onClose, onDecline }: { request: MediaRequest; onClose(): void; onDecline(note: string): void }) {
  const [text, setText] = useState('')
  return <SidePanel title={`Tell ${request.requestedBy} why`} onClose={onClose}>
    <p className="settings-panel__context">Optional. {request.requestedBy} sees it with the request for {request.title}.</p>
    <div style={{ padding: '0 1.125rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
      <TextField id="decline/note" label="Note" value={text} onChange={(v) => setText(v.slice(0, 500))} placeholder="Why it was declined" autoFocus onSubmit={() => onDecline(text)}/>
      <div className="tv-actions">
        <Button id="decline/go" variant="danger" onPress={() => onDecline(text)}>Decline</Button>
        <Button id="decline/cancel" onPress={onClose}>Cancel</Button>
      </div>
    </div>
    <p className="settings-panel__foot">OK on the note types with the TV’s keyboard. Return closes this panel.</p>
  </SidePanel>
}
