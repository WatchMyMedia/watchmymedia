import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { formatRate, liveActivityQuery } from '../../api/activity'
import { offlineDownloadsQuery, useRevokeDownload } from '../../api/offline'
import { useAuth } from '../../authContext'
import { NetworkChip } from '../../components/activity/StreamDetail'
import { Dialog } from '../../components/Dialog'
import { Poster } from '../../components/Poster'
import {
  canRevoke,
  deviceState,
  downloadMark,
  downloadsSummary,
  downloadTitle,
  lastSync,
  progressLine,
  qualityChip,
  sizeLabel,
  timeLeft,
  transfersSummary,
  type Mark,
  type OfflineDownload,
  type OfflineTransfer,
} from '../../lib/offline'
import { useActivityAdmin, useActivityManage } from './shared'

/** ffmpeg's speed: "1.4×", "38×". */
const speed = (x: number) => `${x < 10 ? x.toFixed(1) : Math.round(x)}×`

const MARK_TONES: Record<Mark['tone'], string> = { warn: 'text-warn', accent: 'text-accent', muted: 'text-neutral-400' }

function QualityChip({ quality, mode }: { quality: string; mode: string }) {
  const chip = qualityChip(quality, mode)
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-full border px-2 py-px text-xs font-medium ${chip.transcode ? 'border-warn/60 text-warn' : 'border-emerald-700/60 text-emerald-300'}`}
    >
      {chip.label}
    </span>
  )
}

/**
 * Activity › Downloads (admins; PLAN-DOWNLOADS.md §8,
 * design/mockups/web/ActivityDownloads): what is downloading now and every
 * download kept, with Revoke (from an admin network). A download is not a
 * play, so none of this is in History.
 */
export function Downloads() {
  const admin = useActivityAdmin()
  const manage = useActivityManage()
  const auth = useAuth()
  const list = useQuery({ ...offlineDownloadsQuery, enabled: admin })
  const live = useQuery({ ...liveActivityQuery, enabled: admin })
  const revoke = useRevokeDownload()
  const [asking, setAsking] = useState<OfflineDownload | null>(null)
  if (!admin) return <p className="text-neutral-400">Only the server&rsquo;s admins see everyone&rsquo;s downloads.</p>
  if (list.isPending) return <p className="text-neutral-400">Loading…</p>
  if (list.isError) return <p className="text-red-400">{list.error.message}</p>
  const { items, transfers } = list.data
  const byDownload = new Map(transfers.map((t) => [t.downloadId, t]))
  const sum = transfersSummary(transfers)
  const tc = live.data?.transcoder
  const item = (label: string, value: string) => (
    <div className="min-w-0">
      <dt className="text-xs uppercase tracking-wider text-neutral-500">{label}</dt>
      <dd className="mt-0.5 truncate text-sm text-neutral-100">{value}</dd>
    </div>
  )
  const downloads = tc?.downloads ?? 0
  return (
    <div className="space-y-6">
      <dl className="grid grid-cols-2 gap-4 rounded-lg border border-line bg-surface p-4 sm:grid-cols-4">
        {item('Downloading', sum.downloading)}
        {item('Home network', formatRate(sum.home))}
        {item('Away', formatRate(sum.away))}
        {tc && tc.max > 0
          ? item('Transcoder', `${tc.sessions} of ${tc.max} sessions${downloads > 0 ? ` · ${downloads} download${downloads === 1 ? '' : 's'}` : ''} · ${tc.encoder}`)
          : item('Transcoder', 'Not available')}
      </dl>

      <section aria-labelledby="downloading-now" className="space-y-3">
        <h2 id="downloading-now" className="font-semibold">
          Downloading now
        </h2>
        {transfers.length === 0 ? (
          <div className="rounded-lg border border-dashed border-line-2 px-6 py-8 text-center text-neutral-400">Nothing is downloading right now.</div>
        ) : (
          <div className="grid gap-4 lg:grid-cols-2">
            {transfers.map((t) => (
              <TransferCard key={t.downloadId} t={t} />
            ))}
          </div>
        )}
        <p className="text-[13px] text-neutral-400">
          A download is not a play: it has no row in History. A transcoding download takes one session at low priority and gives way when someone starts watching.
        </p>
      </section>

      <section aria-labelledby="every-download" className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="every-download" className="font-semibold">
            Every download
          </h2>
          {items.length > 0 && <span className="text-[13px] text-neutral-400">{downloadsSummary(items)}</span>}
        </div>
        {items.length === 0 ? (
          <div className="rounded-lg border border-dashed border-line-2 px-6 py-8 text-center text-neutral-400">No one has downloaded anything yet.</div>
        ) : (
          // relative: the sr-only labels are absolute and would otherwise widen the page on a phone.
          <div className="relative overflow-x-auto rounded-lg border border-line bg-surface">
            <table className="w-full min-w-[940px] border-collapse text-sm">
              <thead>
                <tr className="text-left text-xs text-neutral-400">
                  {['Person', 'Device', 'Title', 'Quality', 'On the device'].map((h, i) => (
                    <th key={h} scope="col" className={`whitespace-nowrap py-3 font-medium ${i === 0 ? 'pl-4 pr-3' : 'px-3'}`}>
                      {h}
                    </th>
                  ))}
                  <th scope="col" className="px-3 py-3 text-right font-medium">
                    Size
                  </th>
                  <th scope="col" className="whitespace-nowrap px-3 py-3 font-medium">
                    Last sync
                  </th>
                  <th scope="col" className="py-3 pl-3 pr-4">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((d) => (
                  <DownloadRow key={d.id} d={d} transfer={byDownload.get(d.id)} me={auth.user ?? undefined} onRevoke={manage ? () => setAsking(d) : undefined} />
                ))}
              </tbody>
            </table>
          </div>
        )}
        {revoke.isError && <p className="text-sm text-red-400">{revoke.error.message}</p>}
        <p className="text-[13px] leading-normal text-neutral-400">
          Revoking takes effect when the device next connects: it deletes the download then. Who may download is under{' '}
          <Link to="/settings/users" className="text-neutral-200 underline">
            Settings › Users
          </Link>
          .
        </p>
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
  return (
    <article className="flex gap-4 rounded-lg border border-line bg-surface p-4">
      <div className="w-16 shrink-0">
        <Poster path={t.item.posterPath} size="w185" alt={title} />
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="min-w-0">
          <h3 className="truncate font-semibold">{title}</h3>
          <p className="truncate text-sm text-neutral-400">{subtitle}</p>
        </div>
        <p className="truncate text-sm text-neutral-300">
          <span className="font-medium text-neutral-100">{t.user.name}</span>
          <span className="text-neutral-400"> on </span>
          {t.deviceName}
        </p>
        <div className="flex flex-wrap items-center gap-2 text-xs text-neutral-400">
          <QualityChip quality={t.quality} mode={t.mode} />
          {t.encoder && <span className="font-mono text-neutral-300">{t.encoder}</span>}
          <NetworkChip network={t.network} />
          <span>{formatRate(t.bandwidth)}</span>
          {t.speed ? <span>ffmpeg at {speed(t.speed)}</span> : null}
        </div>
        <div
          className="relative h-1.5 overflow-hidden rounded-full bg-white/10"
          role="progressbar"
          aria-label={`${subtitle || title} downloaded`}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={pct}
        >
          <span className="absolute inset-y-0 left-0 rounded-full bg-accent" style={{ width: `${pct}%` }} />
        </div>
        <div className="flex justify-between gap-2 text-xs tabular-nums text-neutral-400">
          <span>{progressLine(t)}</span>
          <span>{left}</span>
        </div>
      </div>
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
  const td = 'border-t border-line px-3 py-2.5 align-middle'
  return (
    <tr>
      <td className={`${td} pl-4`}>
        <span className="flex items-center gap-2.5">
          <span aria-hidden="true" className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-line-2 text-xs font-semibold text-neutral-200">
            {who.charAt(0).toUpperCase()}
          </span>
          <span className="font-medium">
            {who}
            {d.user?.id === me?.id ? ' (you)' : ''}
          </span>
        </span>
      </td>
      <td className={`${td} text-neutral-300`}>{d.deviceName}</td>
      <td className={td}>
        <span className="block font-medium">{title}</span>
        <span className="block text-[13px] text-neutral-400">{subtitle}</span>
      </td>
      <td className={td}>
        <QualityChip quality={d.quality} mode={d.mode} />
      </td>
      <td className={td}>
        <span className={`block ${state.startsWith('Downloading') ? 'text-accent' : 'text-neutral-200'}`}>{state}</span>
        {mark && (
          <span className={`inline-flex items-center gap-1.5 text-[13px] ${MARK_TONES[mark.tone]}`}>
            <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
            {mark.text}
          </span>
        )}
      </td>
      <td className={`${td} whitespace-nowrap text-right tabular-nums`}>{sizeLabel(d.sizeBytes, d.sizeEstimated)}</td>
      <td className={`${td} whitespace-nowrap text-neutral-400`}>{lastSync(d, transfer !== undefined)}</td>
      <td className={`${td} pr-4 text-right`}>
        {onRevoke && canRevoke(d) && (
          <button
            type="button"
            onClick={onRevoke}
            aria-label={`Revoke ${title}, ${subtitle}, on ${d.deviceName}`}
            className="inline-flex min-h-[44px] items-center px-2 text-sm font-medium text-red-400 hover:text-red-300"
          >
            Revoke
          </button>
        )}
      </td>
    </tr>
  )
}

function RevokeDialog({ d, busy, onClose, onConfirm }: { d: OfflineDownload; busy: boolean; onClose: () => void; onConfirm: () => void }) {
  const { title, subtitle } = downloadTitle(d.item)
  const btn = 'inline-flex min-h-[44px] items-center justify-center rounded-md px-[18px] text-sm'
  return (
    <Dialog title="Revoke this download?" subtitle={[title, subtitle].filter(Boolean).join(' · ')} onClose={onClose} width="max-w-[34rem]">
      <div className="space-y-4 px-6 pb-6 pt-4">
        <p className="text-[15px] leading-relaxed text-neutral-200">
          {d.deviceName || 'The device'} deletes it the next time it connects to this server. Until then it still plays there.
        </p>
        <p className="text-sm leading-relaxed text-neutral-400">{d.user?.name ?? 'They'} can download it again while they may download.</p>
        <div className="flex flex-wrap justify-end gap-2 pt-1">
          <button type="button" onClick={onClose} className={`${btn} border border-line-2 bg-surface font-medium hover:bg-surface-2`}>
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className={`${btn} border border-red-500 bg-red-700 font-semibold text-white hover:bg-red-600 disabled:opacity-50`}
          >
            Revoke
          </button>
        </div>
      </div>
    </Dialog>
  )
}
