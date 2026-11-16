import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { onLibraryChanged } from '../api/events'
import { fetchNewEpisodes, followsQuery } from '../api/follows'
import { serverQuery } from '../api/queries'
import { authScope } from '../lib/authScope'
import { NEW_EPISODES_LOCK, dismissNotice, holdNotifierLock, reminderWorker, showNotice } from '../lib/browserNotices'
import { img } from '../lib/images'
import {
  SETTLE_MS, bannerFor, canFollow, clearBanner, episodeNotices, forgetCursor, loadCursor, moreShowsLine, runCheck, saveBanner, saveCursor, shouldCheck,
  type EpisodeBanner,
} from '../lib/newEpisodes'
import { newEpisodesChanged, useNotifyPermission, useNotifySwitch, usePerson, useStoredBanner } from './newEpisodeHooks'

const quietAction = 'inline-flex min-h-9 items-center justify-center whitespace-nowrap rounded-md border border-line-2 bg-surface px-3.5 text-[13.5px] font-medium hover:bg-surface-2'

/**
 * Checks for new episodes of the shows this person follows while a tab is
 * open (PLAN-NEW-EPISODES.md §6.1, §7.5), from the one tab holding the
 * new episodes' Web Lock: on load and 3 minutes after the last
 * library.changed, only while they follow a show, this browser's switch is
 * on and notifications are allowed. A tab in front shows one banner a
 * check, which every tab of the browser shows until it is opened or
 * dismissed; a hidden one shows system notifications, one a show. Mounted
 * once in the layout; the banner shows off the players.
 */
export function NewEpisodeNotices({ banner }: { banner: boolean }) {
  const server = useQuery(serverQuery)
  const able = canFollow(server.data?.apiLevel)
  const person = usePerson()
  const follows = useQuery({ ...followsQuery, enabled: able })
  const on = useNotifySwitch(person)
  const permission = useNotifyPermission()
  const stored = useStoredBanner(person)
  const [holder, setHolder] = useState(false)
  const running = useRef(false)
  const active = able && !!person && shouldCheck({ follows: follows.data?.follows.length ?? 0, on, permission })

  // Off here, or not allowed: the cursor goes, so turning them on starts from now.
  useEffect(() => {
    if (able && person && (!on || permission !== 'granted')) forgetCursor(person)
  }, [able, person, on, permission])

  useEffect(() => {
    if (!active) return
    void reminderWorker()
    const release = holdNotifierLock(() => setHolder(true), NEW_EPISODES_LOCK)
    return () => {
      release()
      setHolder(false)
    }
  }, [active])

  const check = useCallback(async () => {
    if (!person || running.current) return
    running.current = true
    try {
      const scope = authScope()
      const before = loadCursor(person)
      const reply = await runCheck(before, fetchNewEpisodes)
      // Signed out meanwhile, or another tab (without Web Locks) checked first.
      if (!reply || scope !== authScope() || loadCursor(person) !== before) return
      saveCursor(person, reply.cursor)
      if (!reply.shows.length) return
      if (document.visibilityState === 'visible') {
        const next = bannerFor(reply.shows, reply.cursor)
        if (next) {
          saveBanner(person, next)
          newEpisodesChanged()
        }
      } else {
        for (const n of episodeNotices(reply.shows)) void showNotice({ title: n.title, body: n.body, tag: n.key, path: n.path })
      }
    } finally {
      running.current = false
    }
  }, [person])

  useEffect(() => {
    if (!holder) return
    void check()
    let timer: number | undefined
    const stop = onLibraryChanged(() => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => void check(), SETTLE_MS)
    })
    return () => {
      stop()
      window.clearTimeout(timer)
    }
  }, [holder, check])

  // Off here, or not allowed: no banner either (settings-shows, settings-blocked).
  if (!banner || !stored || !person || !on || permission !== 'granted') return null
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
  const [broken, setBroken] = useState(false)
  const poster = broken ? undefined : img(n.posterPath, 'w92')
  return <section role="status" aria-live="polite" aria-label="New episodes" className="flex flex-col gap-3 rounded-xl border border-line-2 bg-surface-2 p-3.5 shadow-2xl">
    <div className="flex gap-3">
      {poster ? <img src={poster} alt="" onError={() => setBroken(true)} className="aspect-[2/3] w-[42px] shrink-0 self-start rounded bg-surface object-cover" />
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
