import { useCallback, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { onLibraryChanged } from '../api/events'
import { fetchNewEpisodes, followsQuery } from '../api/follows'
import { serverQuery } from '../api/queries'
import { useAuth } from '../authContext'
import { authScope } from '../lib/authScope'
import { NEW_EPISODES_LOCK, dismissNotice, holdNotifierLock, notifyPermission, reminderWorker, showNotice } from '../lib/browserNotices'
import { img } from '../lib/images'
import {
  bannerFor, canFollow, clearBanner, deliveryFor, episodeNotices, forgetPlace, lastLibraryChange, loadCursor, moreShowsLine, noteLibraryChange, runCheck, saveBanner, saveCursor,
  shouldCheck, signedInHere, type EpisodeBanner,
} from '../lib/newEpisodes'
import { createCheckSchedule } from '../lib/newEpisodeSchedule'
import { newEpisodesChanged, useNotifyPermission, useNotifySwitch, usePerson, useSignedInHere, useStoredBanner } from './newEpisodeHooks'

const quietAction = 'inline-flex min-h-9 items-center justify-center whitespace-nowrap rounded-md border border-line-2 bg-surface px-3.5 text-[13.5px] font-medium hover:bg-surface-2'

// One schedule a page, outside the component, so a redraw keeps its timer;
// the last change is in the browser's storage, so another tab keeps it too.
const schedule = createCheckSchedule({
  now: () => Date.now(),
  setTimer: (run, ms) => window.setTimeout(run, ms),
  clearTimer: (timer) => window.clearTimeout(timer as number),
  lastChange: lastLibraryChange,
  noteChange: noteLibraryChange,
})

/**
 * Checks for new episodes of the shows this person follows while a tab is
 * open (PLAN-NEW-EPISODES.md §6.1, §7.5), from the one tab holding the
 * new episodes' Web Lock: when it takes the lock and 3 minutes after the
 * last library.changed (lib/newEpisodeSchedule.ts), only while they follow
 * a show, this browser's switch is on, the page is a secure context and
 * the browser is still signed in as them. The browser's permission isn't
 * needed: a tab in front shows one banner a check, which every tab of the
 * browser shows until it is opened or dismissed; a hidden one shows system
 * notifications, one a show, when they are allowed, and otherwise leaves
 * the banner for when it is seen. Then the cursor moves on. Mounted once in
 * the layout, at one place in every page, and the banner shows off the
 * players.
 */
export function NewEpisodeNotices({ banner }: { banner: boolean }) {
  const server = useQuery(serverQuery)
  const able = canFollow(server.data?.apiLevel)
  const userId = useAuth().user?.id
  const person = usePerson()
  const here = useSignedInHere(userId)
  const follows = useQuery({ ...followsQuery, enabled: able })
  const on = useNotifySwitch(person)
  const permission = useNotifyPermission()
  const secure = typeof window !== 'undefined' && window.isSecureContext
  const stored = useStoredBanner(person)
  const [holder, setHolder] = useState(false)
  const active = able && !!person && here && shouldCheck({ follows: follows.data?.follows.length ?? 0, on, secure })

  // Off here: the cursor and the banner go, so turning it on starts from now.
  useEffect(() => {
    if (!able || !person || on) return
    forgetPlace(person)
    newEpisodesChanged()
  }, [able, person, on])

  // The worker shows a hidden tab's notifications, once they are allowed.
  useEffect(() => {
    if (active && permission === 'granted') void reminderWorker()
  }, [active, permission])

  // Every tab records changes, so the one that takes the lock knows when the window ends.
  useEffect(() => onLibraryChanged((reason) => schedule.libraryChanged(reason)), [])

  useEffect(() => {
    if (!active) return
    const release = holdNotifierLock(() => setHolder(true), NEW_EPISODES_LOCK)
    return () => {
      release()
      setHolder(false)
    }
  }, [active])

  const check = useCallback(async () => {
    if (!person || !signedInHere(userId)) return
    const scope = authScope()
    const before = loadCursor(person)
    const reply = await runCheck(before, fetchNewEpisodes)
    // Signed out or in as someone else meanwhile, or another tab (without Web Locks) checked first.
    if (!reply || scope !== authScope() || !signedInHere(userId) || loadCursor(person) !== before) return
    // Show, then keep the cursor (§6.1). A hidden tab without the permission leaves the banner for when it is seen.
    if (reply.shows.length && deliveryFor(document.visibilityState === 'visible', notifyPermission()) === 'banner') {
      const next = bannerFor(reply.shows, reply.cursor)
      if (next) {
        saveBanner(person, next)
        newEpisodesChanged()
      }
    } else if (reply.shows.length) {
      await Promise.all(episodeNotices(reply.shows).map((n) => showNotice({ title: n.title, body: n.body, tag: n.key, path: n.path })))
    }
    saveCursor(person, reply.cursor)
  }, [person, userId])

  useEffect(() => {
    if (!holder) return
    schedule.start(check)
    return () => schedule.stop()
  }, [holder, check])

  // Off here, or on a plain http:// address: no banner (settings-shows, settings-http).
  if (!banner || !stored || !person || !on || !secure) return null
  const close = () => {
    dismissNotice(stored.key)
    clearBanner(person)
    newEpisodesChanged()
  }
  return <NewEpisodeBanner banner={stored} onClose={close} />
}

/**
 * The banner-one and banner-more boards: quieter than a reminder's, with
 * no filled button, and it takes no focus. Open goes to the show's page
 * with the earliest new episode on top; the link under the divider opens
 * Home for the rest.
 */
function NewEpisodeBanner({ banner, onClose }: { banner: EpisodeBanner; onClose: () => void }) {
  const n = banner.notice
  // The picture that failed, not a flag, so the next banner's still loads.
  const [broken, setBroken] = useState('')
  const src = img(n.posterPath, 'w92')
  const poster = src && src !== broken ? src : undefined
  return <section role="status" aria-live="polite" aria-label="New episodes" className="flex flex-col gap-3 rounded-xl border border-line-2 bg-surface-2 p-3.5 shadow-2xl">
    <div className="flex gap-3">
      {poster ? <img key={poster} src={poster} alt="" onError={() => setBroken(poster)} className="aspect-[2/3] w-[42px] shrink-0 self-start rounded bg-surface object-cover" />
        : <span aria-hidden="true" className="aspect-[2/3] w-[42px] shrink-0 self-start rounded bg-surface ring-1 ring-line-2" />}
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-[11.5px] font-bold uppercase tracking-wider text-accent">{n.heading}</span>
        <span className="text-[15px] font-semibold">{n.show}</span>
        <span className="text-[13px] text-neutral-400">{n.line}</span>
      </div>
    </div>
    <div className="flex gap-2">
      <Link to={n.path} onClick={onClose} aria-label={n.openLabel} className={quietAction}>Open</Link>
      <button type="button" onClick={onClose} className={quietAction}>Dismiss</button>
    </div>
    {banner.more > 0 && <Link to="/" onClick={onClose} className="block border-t border-line pt-2.5 text-[13.5px] text-neutral-300 underline decoration-neutral-600 underline-offset-[3px] hover:text-neutral-100">{moreShowsLine(banner.more)}</Link>}
  </section>
}
