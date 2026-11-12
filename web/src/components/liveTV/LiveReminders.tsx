import { useEffect, useState, type ReactNode } from 'react'
import { useGuideExtras, useReminders } from './useGuideExtras'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { liveTVChannelsQuery, useLiveReminder } from '../../api/queries'
import type { LiveChannel, LiveProgramme } from '../../api/liveTV'
import {
  DISMISSED_KEY, askToNotify, claimNotice, dismissNotice, dismissedNotices, holdNotifierLock, noteAsked, notifyPermission, onReminderOpened, reminderWorker, shouldExplain,
  showReminder,
} from '../../lib/browserNotices'
import {
  dueReminders, leadText, movedTo, nextReminderAt, noticeKey, reminderNotice, reminderWhen, remindState, type LiveReminder,
} from '../../lib/liveReminders'

export function BellIcon({ size = 15, filled, crossed }: { size?: number; filled?: boolean; crossed?: boolean }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M6 16v-5a6 6 0 1 1 12 0v5l1.5 2h-15z" /><path d="M10 20.5a2 2 0 0 0 4 0" fill="none" />{crossed && <path d="M4 4l16 16" />}
  </svg>
}

export const primaryAction = 'inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-md btn-primary px-4.5 text-sm font-semibold disabled:opacity-50'
export const secondaryAction = 'inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 disabled:opacity-50'
const textAction = 'inline-flex min-h-11 items-center whitespace-nowrap px-2 text-sm text-neutral-300 underline hover:text-accent disabled:opacity-50'

/**
 * Remind me for a programme that starts later, and its states: set (with
 * the lead time), moved by a listings refresh, with Remove. The first
 * Remind me in a browser that could notify explains before the browser
 * asks (the LiveReminder board's permission state); either answer sets the
 * reminder, which then shows at least as a banner.
 */
export function RemindControls({ programme, reminder, lead, now, primary = true, compact }: { programme: LiveProgramme; reminder?: LiveReminder; lead: number; now: number; primary?: boolean; compact?: boolean }) {
  const change = useLiveReminder()
  const [asking, setAsking] = useState(false)
  const state = remindState(programme, reminder ?? programme.reminder, lead, now)
  const set = () => change.mutate({ programmeId: programme.id })
  if (state === 'remind') return <>
    <button type="button" disabled={change.isPending} className={primary ? primaryAction : secondaryAction} onClick={() => (shouldExplain() ? setAsking(true) : set())}><BellIcon />Remind me</button>
    {asking && <NotifyExplainer title={programme.title} lead={lead} onAnswer={(allow) => { setAsking(false); set(); if (allow) void askToNotify(); else noteAsked() }} />}
    {change.isError && <span role="alert" className="text-sm text-warn">{change.error.message}</span>}
  </>
  if (state !== 'set' && state !== 'moved') return null
  const label = state === 'moved' ? movedTo(Date.parse(programme.start), now) : compact ? 'Reminder set' : `Reminder set · ${leadText(lead)}`
  return <>
    <span role="status" className="inline-flex min-h-11 items-center gap-2 whitespace-nowrap rounded-md border border-accent/55 px-3.5 text-sm font-semibold text-accent"><BellIcon filled />{label}</span>
    <button type="button" disabled={change.isPending || !reminder} onClick={() => reminder && change.mutate({ reminderId: reminder.id })} aria-label={`Remove the reminder for ${programme.title}`} className={textAction}>Remove</button>
  </>
}

/** Before the browser's own question: what Allow will do (the LiveReminder board's permission dialog). */
function NotifyExplainer({ title, lead, onAnswer }: { title: string; lead: number; onAnswer: (allow: boolean) => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onAnswer(false) }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [onAnswer])
  return <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/65 p-4 pt-24 sm:pt-36">
    <section role="dialog" aria-modal="true" aria-labelledby="notify-head" className="flex w-full max-w-[34rem] flex-col gap-3.5 rounded-2xl border border-line-2 bg-surface-2 p-5 shadow-2xl sm:p-6">
      <div className="flex items-start gap-3.5">
        <span aria-hidden="true" className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-accent/10 text-accent"><BellIcon size={20} /></span>
        <div className="min-w-0 flex-1 space-y-2">
          <h2 id="notify-head" className="text-xl font-semibold leading-snug">Get reminders from this browser?</h2>
          <p className="text-[15px] leading-relaxed text-neutral-200">Your browser will ask whether WatchMyMedia may show notifications. Allow it, and a reminder comes {lead === 0 ? 'when' : `${lead} min before`} {title} starts, even when this tab isn’t in front.</p>
          <p className="text-[13px] leading-relaxed text-neutral-400">Reminders show while WatchMyMedia is open in a tab. Without notifications, the reminder is still set and shows as a banner in WatchMyMedia.</p>
        </div>
      </div>
      <div className="mt-1 flex flex-wrap justify-end gap-2">
        <button type="button" className={secondaryAction} onClick={() => onAnswer(false)}>Not now</button>
        <button type="button" autoFocus className={primaryAction} onClick={() => onAnswer(true)}>Continue</button>
      </div>
    </section>
  </div>
}

/** The guide's Reminders list: each with its time, moved or gone, and Remove; the lead time and where reminders show. */
export function RemindersList({ items, lead, now, channels, onOpen, onClose, heading = 'h2' }: { items: LiveReminder[]; lead: number; now: number; channels: Pick<LiveChannel, 'id' | 'logoUrl'>[]; onOpen: (r: LiveReminder) => void; onClose: () => void; heading?: 'h2' | 'h3' }) {
  const change = useLiveReminder()
  const H = heading
  return <>
    <div className="flex items-center gap-2 py-2 pl-4 pr-2">
      <H id="reminders-head" className="flex-1 text-base font-semibold">Reminders</H>
      <button type="button" onClick={onClose} aria-label="Close reminders" className="grid h-11 w-11 place-items-center rounded-md border border-line-2 bg-surface hover:bg-surface-2"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
    </div>
    {items.length > 0 ? <ul>{items.map((r) => {
      const logo = channels.find((c) => c.id === r.channelId)?.logoUrl
      return <li key={r.id} className="flex items-start gap-3 border-t border-line px-4 py-3">
        <span className="mt-0.5 hidden h-6 w-9 shrink-0 sm:block">{logo && <img src={logo} alt="" className="h-6 w-9 object-contain object-left" />}</span>
        <button type="button" onClick={() => onOpen(r)} aria-label={r.state === 'gone' ? `${r.title}, no longer in the listings` : `Open ${r.title} in the guide`} className="group flex min-w-0 flex-1 flex-col gap-0.5 text-left">
          <span className="text-[15px] font-semibold group-hover:text-accent">{r.title}</span>
          {episodeOf(r) && <span className="hidden text-[13px] text-neutral-300 sm:block">{episodeOf(r)}</span>}
          <span className="text-[13px] tabular-nums text-neutral-400">{reminderWhen(r, now)}</span>
          {r.state === 'moved' && <span className="text-[13px] font-semibold text-accent">{movedTo(Date.parse(r.start), now)}</span>}
          {r.state === 'gone' && <span className="text-[13px] font-semibold text-warn">No longer in the listings</span>}
        </button>
        <button type="button" disabled={change.isPending} onClick={() => change.mutate({ reminderId: r.id })} aria-label={`Remove the reminder for ${r.title}`} className="inline-flex min-h-9 items-center px-2 text-sm text-neutral-300 underline hover:text-accent">Remove</button>
      </li>
    })}</ul> : <p className="border-t border-line px-4 py-3 text-sm text-neutral-400">No reminders. Choose a later programme and press Remind me.</p>}
    <div className="space-y-1 border-t border-line px-4 pb-3.5 pt-3 text-[13px] leading-relaxed text-neutral-400">
      <p>Each comes {lead === 0 ? 'when it starts' : `${lead} min before it starts`}. <Link to="/settings/playback" className="text-neutral-300 underline">Change it in Settings › Playback</Link></p>
      <p>Reminders show while WatchMyMedia is open in a tab.</p>
    </div>
  </>
}

function episodeOf(r: LiveReminder): string {
  const number = r.season != null && r.episode != null ? (r.season >= 1000 ? `${r.season} E${r.episode}` : `S${r.season} E${r.episode}`) : ''
  return [number, r.episodeTitle].filter(Boolean).join(' · ')
}

/**
 * Delivers reminders while this page is open (§6.5): a banner with Watch
 * and Dismiss in a visible tab, and from the one tab holding the Web Lock a
 * system notification through the service worker once the person allowed
 * them. Mounted once in the layout; the banner shows off the players.
 */
export function ReminderNotices({ banner }: { banner: boolean }) {
  const extras = useGuideExtras()
  const { items, lead } = useReminders(extras)
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: extras && items.length > 0 })
  const navigate = useNavigate()
  const [now, setNow] = useState(() => Date.now())
  const [notifier, setNotifier] = useState(false)
  const [dismissed, setDismissed] = useState(dismissedNotices)

  // Wake for the next reminder, and every 15 s while one shows ("Starting in 4 min").
  const due = dueReminders(items, lead, now)
  const next = nextReminderAt(items, lead, now)
  useEffect(() => {
    if (!items.length) return
    const wait = due.length ? 15_000 : next !== undefined ? Math.min(Math.max(next - Date.now(), 1000), 2 ** 31 - 1) : undefined
    if (wait === undefined) return
    const timer = window.setTimeout(() => setNow(Date.now()), wait)
    return () => window.clearTimeout(timer)
  }, [items.length, due.length, next, now])
  // A tab in the background comes back to the current time at once.
  useEffect(() => {
    const seen = () => { setNow(Date.now()); setDismissed(dismissedNotices()) }
    const storage = (e: StorageEvent) => { if (e.key === DISMISSED_KEY) setDismissed(dismissedNotices()) }
    document.addEventListener('visibilitychange', seen)
    window.addEventListener('storage', storage)
    return () => { document.removeEventListener('visibilitychange', seen); window.removeEventListener('storage', storage) }
  }, [])
  useEffect(() => (extras ? holdNotifierLock(() => setNotifier(true)) : undefined), [extras])
  useEffect(() => {
    if (!extras) return
    if (notifyPermission() === 'granted') void reminderWorker()
    return onReminderOpened((path) => void navigate({ to: path }))
  }, [extras, navigate])

  const dueKeys = due.map(noticeKey).join(' ')
  useEffect(() => {
    if (!notifier || notifyPermission() !== 'granted') return
    for (const r of dueReminders(items, lead, Date.now())) {
      const key = noticeKey(r)
      // A tab in front shows the banner instead.
      if (document.visibilityState === 'visible' || !claimNotice(key)) continue
      const words = reminderNotice(r, Date.now())
      void showReminder({ title: words.notification, body: words.line, tag: key, path: r.channelId ? `/live-tv/watch/${r.channelId}` : undefined })
    }
    // Only when what is due changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notifier, dueKeys])

  const shown = banner ? due.find((r) => !dismissed.has(noticeKey(r))) : undefined
  if (!shown) return null
  const words = reminderNotice(shown, now)
  const close = () => { dismissNotice(noticeKey(shown)); setDismissed(dismissedNotices()) }
  const channel = channels.data?.items.find((c) => c.id === shown.channelId)
  // The programme's series card from the channel's now or next, else its logo.
  const picture = [channel?.now, channel?.next].find((p) => p?.id === shown.programmeId)?.pictureUrl
  return <ReminderBanner heading={words.heading} title={words.title} line={words.line} picture={picture} logo={channel?.logoUrl} watch={shown.channelId ? { channelId: shown.channelId, number: shown.number } : undefined} onWatch={close} onDismiss={close} />
}

function ReminderBanner({ heading, title, line, picture, logo, watch, onWatch, onDismiss }: { heading: string; title: string; line: string; picture?: string; logo?: string; watch?: { channelId: string; number: string }; onWatch: () => void; onDismiss: () => void }): ReactNode {
  const [broken, setBroken] = useState('')
  return <section role="status" aria-live="polite" aria-label="Live TV reminder" className="fixed inset-x-4 top-16 z-40 flex flex-col gap-3.5 rounded-xl border border-line-2 bg-surface-2 p-4 shadow-2xl sm:left-auto sm:right-12 sm:w-100">
    <div className="flex gap-3.5">
      {picture && broken !== picture ? <img key={picture} src={picture} alt="" onError={() => setBroken(picture)} className="aspect-video w-28 shrink-0 self-start rounded-md bg-surface object-cover" />
        : logo && <span aria-hidden="true" className="grid aspect-video w-28 shrink-0 place-items-center self-start rounded-md bg-surface"><img src={logo} alt="" className="h-1/2 w-1/2 object-contain" /></span>}
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-xs font-bold uppercase tracking-wider text-accent">{heading}</span>
        <span className="text-base font-semibold">{title}</span>
        <span className="text-[13px] text-neutral-400">{line}</span>
      </div>
    </div>
    <div className="flex gap-2">
      {watch && <Link to="/live-tv/watch/$channelId" params={{ channelId: watch.channelId }} onClick={onWatch} className={`${primaryAction} flex-1`}><svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4v16l13-8z" /></svg>Watch {watch.number}</Link>}
      <button type="button" onClick={onDismiss} className={secondaryAction}>Dismiss</button>
    </div>
  </section>
}
