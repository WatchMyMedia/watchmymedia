import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveActivityQuery } from '../../api/activity'
import { offlineDownloadsQuery, useRevokeDownload } from '../../api/offline'
import { useAuth } from '../../authContext'
import { Avatar, StreamFacts } from '../../components/activity/StreamDetail'
import { cardTech, tile } from '../../components/activity/styles'
import { copyWords, downloadsInShort, NETWORK_WORDS, rateWords, transferWhy } from '../../lib/activityWords'
import { formatBytes } from '../../lib/format'
import { Dialog } from '../../components/Dialog'
import { Poster } from '../../components/Poster'
import {
  canRevoke,
  deviceState,
  downloadMark,
  downloadTitle,
  lastSync,
  progressLine,
  sizeLabel,
  timeLeft,
  type Mark,
  type OfflineDownload,
  type OfflineTransfer,
} from '../../lib/offline'
import { useActivityAdmin, useActivityManage } from './shared'

/** ffmpeg's speed: "1.4×", "38×". */
const speed = (x: number) => `${x < 10 ? x.toFixed(1) : Math.round(x)}×`

const MARK_TONES: Record<Mark['tone'], string> = { warn: 'text-warn', accent: 'text-accent', muted: 'text-neutral-400' }

/** How many rows Every download shows before Show all. */
const FIRST_ROWS = 8

/**
 * Activity › Downloads (admins): what is on people's devices in short, what
 * is downloading now, and every download kept, with Revoke (from an admin
 * network). A download is not a play, so none of this is in History.
 */
export function Downloads() {
  const admin = useActivityAdmin()
  const manage = useActivityManage()
  const auth = useAuth()
  const list = useQuery({ ...offlineDownloadsQuery, enabled: admin })
  const live = useQuery({ ...liveActivityQuery, enabled: admin })
  const revoke = useRevokeDownload()
  const [asking, setAsking] = useState<OfflineDownload | null>(null)
  const [person, setPerson] = useState<number | undefined>()
  const [all, setAll] = useState(false)
  if (!admin) return <p className="mt-6 text-neutral-400">Only the server&rsquo;s admins see everyone&rsquo;s downloads.</p>
  if (list.isPending) return <p className="mt-6 text-neutral-400">Loading…</p>
  if (list.isError) return <p className="mt-6 text-red-400">{list.error.message}</p>
  const { items, transfers } = list.data
  const byDownload = new Map(transfers.map((t) => [t.downloadId, t]))
  const short = downloadsInShort(items)
  const smaller = transfers.filter((t) => t.mode === 'transcode').length
  const tc = live.data?.transcoder
  const people = [...new Map(items.filter((d) => d.user).map((d) => [d.user!.id, d.user!.name])).entries()]
  const shown = items.filter((d) => person === undefined || d.user?.id === person)
  const rows = all ? shown : shown.slice(0, FIRST_ROWS)
  return (
    <div>
      <section aria-label="Downloads in short" className="mt-[22px] grid max-w-[760px] grid-cols-[repeat(auto-fit,minmax(min(260px,100%),1fr))] gap-3.5">
        <div className={tile}>
          <span className="text-[13px] text-neutral-400">On devices</span>
          <span className="text-[26px] font-semibold leading-tight tracking-[-.02em]">
            {short.titles === 0 ? 'Nothing yet' : short.titles === 1 ? '1 title' : `${short.titles} titles`}
            {short.bytes > 0 ? ` · ${formatBytes(short.bytes)}` : ''}
          </span>
          {short.titles > 0 && (
            <span className="text-[13px] text-neutral-300">
              On {short.devices === 1 ? '1 device' : `${short.devices} devices`}, for {short.people === 1 ? '1 person' : `${short.people} people`}
            </span>
          )}
        </div>
        <div className={tile}>
          <span className="text-[13px] text-neutral-400">Downloading now</span>
          <span className="text-[26px] font-semibold leading-tight tracking-[-.02em]">{transfers.length}</span>
          <span className="text-[13px] text-neutral-300">
            {smaller ? `The server is making ${smaller === 1 ? 'one smaller copy' : `${smaller} smaller copies`}` : transfers.length ? 'Each sent as it is' : 'Nothing is downloading'}
            {tc && tc.max > 0 && smaller > 0 ? ` · ${tc.sessions} of ${tc.max} converter sessions in use` : ''}
          </span>
        </div>
      </section>

      {transfers.length > 0 && (
        <section aria-labelledby="h-downloading" className="mt-8">
          <div className="rowh">
            <h2 id="h-downloading">Downloading now</h2>
          </div>
          <div className="mt-3.5 flex max-w-[760px] flex-col gap-4">
            {transfers.map((t) => (
              <TransferCard key={t.downloadId} t={t} />
            ))}
          </div>
          <p className="mt-3 max-w-[760px] text-[13px] text-neutral-400">
            A download is not a play: it has no row in History. A smaller copy takes one converter session at low priority and gives way when someone starts watching.
          </p>
        </section>
      )}

      <section aria-labelledby="h-every" className="mt-8">
        <div className="rowh">
          <h2 id="h-every">Every download</h2>
          {items.length > 0 && <span className="note">{shown.length === 1 ? '1 title' : `${shown.length} titles`} · newest first</span>}
          {people.length > 1 && (
            <select aria-label="Person" value={person ?? ''} onChange={(e) => setPerson(e.target.value ? Number(e.target.value) : undefined)} className="ml-auto min-h-[34px] rounded-lg border border-line-2 bg-surface px-2.5 text-[13px] text-neutral-200">
              <option value="">Everyone</option>
              {people.map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          )}
        </div>
        {items.length === 0 ? (
          <div className="mt-3.5 rounded-xl border border-dashed border-line-2 px-6 py-8 text-center text-neutral-400">No one has downloaded anything yet.</div>
        ) : (
          <div className="panel mt-3.5">
            {/* relative: the sr-only labels are absolute and would otherwise widen the page on a phone. */}
            <div className="relative overflow-x-auto">
              <table className="tbl min-w-[900px]">
                <thead>
                  <tr>
                    <th scope="col">Person</th>
                    <th scope="col">Device</th>
                    <th scope="col">Title</th>
                    <th scope="col">Copy</th>
                    <th scope="col" className="text-right!">
                      Size
                    </th>
                    <th scope="col">Last synced</th>
                    <th scope="col">
                      <span className="sr-only">Revoke</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((d) => (
                    <DownloadRow key={d.id} d={d} transfer={byDownload.get(d.id)} me={auth.user ?? undefined} onRevoke={manage ? () => setAsking(d) : undefined} />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2.5 border-t border-line px-3.5 py-3">
              {shown.length > FIRST_ROWS && (
                <button type="button" onClick={() => setAll((a) => !a)} className="btn btn-sm">
                  {all ? 'Show fewer' : `Show all ${shown.length}`}
                </button>
              )}
              <span className="text-[13px] text-neutral-400">
                Revoking removes a download from the device the next time it connects. The person can download it again while they may download; who may is under{' '}
                <Link to="/settings/users" className="text-neutral-200 underline">
                  Settings › Users
                </Link>
                .
              </span>
            </div>
          </div>
        )}
        {revoke.isError && <p className="mt-2 text-sm text-red-400">{revoke.error.message}</p>}
      </section>

      {asking && (
        <RevokeDialog
          d={asking}
          busy={revoke.isPending}
          onClose={() => setAsking(null)}
          onConfirm={() => revoke.mutate(asking.id, { onSuccess: () => setAsking(null) })}
        />
      )}
    </div>
  )
}

function TransferCard({ t }: { t: OfflineTransfer }) {
  const { title, subtitle } = downloadTitle(t.item)
  const pct = Math.min(100, Math.round(t.progress * 100))
  const left = timeLeft(t)
  const smaller = t.mode === 'transcode'
  return (
    <article className="panel">
      <div className="flex gap-4 p-4">
        <div className="w-[72px] shrink-0 self-start sm:w-[88px]">
          <Poster path={t.item.posterPath} size="w185" alt="" />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-2.5">
          <div>
            <h3 className="text-base font-semibold leading-snug">{title}</h3>
            <p className="text-[12.5px] text-neutral-400">{subtitle}</p>
          </div>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[13.5px]">
            <span className="inline-flex items-center gap-2 font-semibold">
              <Avatar name={t.user.name} />
              {t.user.name}
            </span>
            <span className="text-neutral-400">
              · {[t.deviceName, NETWORK_WORDS[t.network] ?? ''].filter(Boolean).join(' · ')}
            </span>
          </p>
          <div>
            <span className="meter" role="img" aria-label={`${pct}% downloaded`}>
              <i style={{ width: `${pct}%` }} />
            </span>
            <p className="mt-1.5 text-[13px] tabular-nums text-neutral-300">{[progressLine(t), left].filter(Boolean).join(' · ')}</p>
          </div>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1.5 text-[13px]">
            <span className={`pill ${smaller ? 'pill-warn' : ''}`}>{smaller ? 'Smaller copy' : 'Original'}</span>
            <span className="text-neutral-400">{transferWhy(t)}</span>
          </p>
        </div>
      </div>
      <details className={cardTech}>
        <summary>Transfer details</summary>
        <div className="tech-body pt-1">
          <StreamFacts
            facts={[
              ['Copy', copyWords(t.quality, t.mode)],
              ...(t.encoder ? [['Encoder', `${t.encoder}${t.speed ? ` · ${speed(t.speed)} speed` : ''}`] as [string, string]] : []),
              ['Sending at', rateWords(t.bandwidth)],
              ['Started', new Date(t.startedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })],
            ]}
          />
        </div>
      </details>
    </article>
  )
}

function DownloadRow({
  d,
  transfer,
  me,
  onRevoke,
}: {
  d: OfflineDownload
  transfer?: OfflineTransfer
  me?: { id: number; name: string }
  /** Away from an admin network there is no Revoke. */
  onRevoke?: () => void
}) {
  const { title, subtitle } = downloadTitle(d.item)
  const who = d.user?.name ?? ''
  const mark = downloadMark(d, me?.name)
  const state = deviceState(d, transfer)
  return (
    <tr>
      <td>
        {d.user ? (
          <Link to="/activity/users/$userId" params={{ userId: String(d.user.id) }} className="flex items-center gap-2 font-medium hover:text-accent">
            <Avatar name={who} />
            {who}
            {d.user.id === me?.id ? ' (you)' : ''}
          </Link>
        ) : (
          <span className="text-neutral-400">Someone removed</span>
        )}
      </td>
      <td className="whitespace-nowrap text-neutral-300">{d.deviceName}</td>
      <td>
        <span className="block font-medium">{title}</span>
        <span className="block text-[12.5px] text-neutral-400">{subtitle}</span>
      </td>
      <td className="whitespace-nowrap text-neutral-300">{copyWords(d.quality, d.mode)}</td>
      <td className="whitespace-nowrap text-right tabular-nums">{sizeLabel(d.sizeBytes, d.sizeEstimated)}</td>
      <td>
        <span className="flex flex-wrap items-center gap-x-2 gap-y-1.5 text-neutral-300">
          <span className="whitespace-nowrap">{transfer ? state : lastSync(d, false)}</span>
          {mark && (
            <span className={`inline-flex items-center gap-1.5 text-[13px] ${MARK_TONES[mark.tone]}`}>
              <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-current" />
              {mark.text}
            </span>
          )}
        </span>
      </td>
      <td className="text-right">
        {onRevoke && canRevoke(d) && (
          <button type="button" onClick={onRevoke} aria-label={`Revoke ${title}${subtitle ? `, ${subtitle}` : ''}, on ${d.deviceName}`} className="btn btn-sm">
            Revoke
          </button>
        )}
      </td>
    </tr>
  )
}

function RevokeDialog({ d, busy, onClose, onConfirm }: { d: OfflineDownload; busy: boolean; onClose: () => void; onConfirm: () => void }) {
  const { title, subtitle } = downloadTitle(d.item)
  return (
    <Dialog title="Revoke this download?" subtitle={[title, subtitle].filter(Boolean).join(' · ')} onClose={onClose} width="max-w-[34rem]">
      <div className="space-y-4 px-6 pb-6 pt-4">
        <p className="text-[15px] leading-relaxed text-neutral-200">
          {d.deviceName || 'The device'} deletes it the next time it connects to this server. Until then it still plays there.
        </p>
        <p className="text-sm leading-relaxed text-neutral-400">{d.user?.name ?? 'They'} can download it again while they may download.</p>
        <div className="flex flex-wrap justify-end gap-2 pt-1">
          <button type="button" onClick={onClose} className="btn">
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className="btn btn-primary"
          >
            Revoke
          </button>
        </div>
      </div>
    </Dialog>
  )
}
