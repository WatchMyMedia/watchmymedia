import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { followsQuery, useRemoveFollow, type Follow } from '../../api/follows'
import { askForNewEpisodes, newEpisodesChanged, useNotifyPermission, useNotifySwitch, usePerson } from '../../components/newEpisodeHooks'
import { useGuideExtras } from '../../components/liveTV/useGuideExtras'
import { Poster } from '../../components/Poster'
import { Button, Card, HubPage, Message, Section, Switch } from '../../components/settings/Frame'
import { browserState, followLine, setNotifySwitch, stopFailLine, type BrowserState } from '../../lib/newEpisodes'
import { settingsCrumbs } from '../../lib/settingsRoutes'

/**
 * Settings › You › Notifications (PLAN-NEW-EPISODES.md §7.2; the
 * settings-* boards): this browser's New episodes switch first, then the
 * shows the person follows, with Stop notifying. The switch is kept in the
 * browser for this person; the follows are the server's, the same on every
 * device, and redraw on follows.changed.
 */
export function NotificationsSettings() {
  const reminders = useGuideExtras()
  return (
    <HubPage crumbs={settingsCrumbs('notifications')} title="Notifications" lede="The shows you follow are the same on every device. Each device has its own switch.">
      <Section title="On this browser">
        <ThisBrowser />
      </Section>
      <Section title="Shows you follow">
        <FollowedShows />
      </Section>
      {reminders && (
        <p className="text-[13.5px] text-neutral-400">
          Live TV reminders are in <Link to="/settings/playback" className="text-neutral-200 underline">Playback</Link>.
        </p>
      )}
    </HubPage>
  )
}

const blockedWords: Partial<Record<BrowserState, [string, string]>> = {
  blocked: ['Notifications are blocked for this site.', 'Allow them in your browser’s site settings.'],
  http: ['This browser can’t show notifications from a plain http:// address.', 'Open WatchMyMedia over HTTPS to get them.'],
  // Not on the boards: a browser with no notifications at all, such as Safari on an iPhone outside a Home Screen app.
  unsupported: ['This browser can’t show notifications.', 'Your other devices still notify you, and new episodes show on Home.'],
}

/**
 * The switch: on by default once the browser allows notifications. Not
 * asked yet, it shows off, and turning it on asks the browser at once.
 * Blocked, on a plain http:// address or without notifications, a message
 * says why in place of a working switch, and the limits line is left out.
 */
function ThisBrowser() {
  const person = usePerson()
  const permission = useNotifyPermission()
  const on = useNotifySwitch(person)
  const [asking, setAsking] = useState(false)
  const state = browserState(permission, on, typeof window !== 'undefined' && window.isSecureContext)
  const why = blockedWords[state]
  const change = (next: boolean) => {
    if (!person) return
    if (state === 'ask') {
      if (!next) return
      setAsking(true)
      void askForNewEpisodes(person).finally(() => setAsking(false))
      return
    }
    setNotifySwitch(person, next)
    newEpisodesChanged()
  }
  return (
    <div className="space-y-2.5">
      {why && <Message tone="warn" title={why[0]}>{why[1]}</Message>}
      <Card>
        <div className={`flex items-center gap-4 px-5 py-3.5 ${why ? 'opacity-55' : ''}`}>
          <div className="min-w-0 flex-1">
            <h4 className="font-medium">New episodes</h4>
            <p className="mt-0.5 text-sm text-neutral-400">A notification when a show you follow gets a new episode</p>
          </div>
          <Switch label="New episodes" checked={state === 'on'} disabled={!!why || asking || !person} onChange={change} />
        </div>
      </Card>
      {!why && <p className="text-[13.5px] text-neutral-400">Only while WatchMyMedia is open in a tab.</p>}
    </div>
  )
}

/** Newest follow first; Stop notifying removes a row at once, with no dialog, and puts it back if the server refuses. */
function FollowedShows() {
  const follows = useQuery(followsQuery)
  // Refusals by show, from the hook's own callbacks, which come for every press.
  const [failed, setFailed] = useState<ReadonlySet<number>>(new Set())
  const mark = (tmdbId: number, on: boolean) =>
    setFailed((s) => {
      const next = new Set(s)
      if (on) next.add(tmdbId)
      else next.delete(tmdbId)
      return next
    })
  const remove = useRemoveFollow({ onFailed: (tmdbId) => mark(tmdbId, true) })
  const stop = (f: Follow) => {
    mark(f.tmdbId, false)
    remove.mutate(f.tmdbId)
  }

  if (follows.isPending) return <LoadingFollows />
  if (follows.isError) {
    return (
      <Message tone="err" title="Couldn’t load the shows you follow" actions={<Button small onClick={() => void follows.refetch()}>Try again</Button>}>
        The server didn’t answer. Check your connection and try again.
      </Message>
    )
  }
  if (!follows.data.follows.length) {
    return (
      <Card padded>
        <p>You don’t follow any shows yet. Choose Notify me on a show’s page.</p>
      </Card>
    )
  }
  return (
    <Card>
      {follows.data.follows.map((f) => (
        <FollowRow key={f.tmdbId} follow={f} failed={failed.has(f.tmdbId)} onStop={() => stop(f)} />
      ))}
    </Card>
  )
}

function FollowRow({ follow: f, failed, onStop }: { follow: Follow; failed: boolean; onStop: () => void }) {
  const line = followLine(f)
  const showId = f.show ? String(f.show.id) : undefined
  return (
    <div className="flex items-center gap-4 px-5 py-3.5">
      <div className="flex min-w-0 flex-1 items-center gap-3">
        {showId ? (
          <Link to="/shows/$showId" params={{ showId }} tabIndex={-1} aria-hidden="true" className="w-10 shrink-0">
            <Poster path={f.show?.posterPath} size="w92" alt="" className="rounded" />
          </Link>
        ) : (
          <span aria-hidden="true" className="aspect-[2/3] w-10 shrink-0 rounded border border-dashed border-line-2" />
        )}
        <div className="min-w-0">
          <h4 className="font-medium break-words">
            {showId ? <Link to="/shows/$showId" params={{ showId }} className="hover:underline">{f.title}</Link> : f.title}
          </h4>
          {failed ? (
            <p role="alert" className="mt-0.5 text-sm text-red-400">{stopFailLine(f.title)}</p>
          ) : (
            <p className={`mt-0.5 text-sm ${line.gone ? 'text-warn' : 'text-neutral-400'}`}>{line.text}</p>
          )}
        </div>
      </div>
      <Button small className="shrink-0" onClick={onStop} aria-label={`Stop notifying me of new episodes of ${f.title}`}>Stop notifying</Button>
    </div>
  )
}

/** The list's shape while it loads. */
function LoadingFollows() {
  return (
    <div aria-busy="true" aria-label="Loading" className="divide-y divide-line rounded-xl border border-line bg-surface">
      {[0, 1].map((i) => (
        <div key={i} className="flex items-center gap-3 px-5 py-3.5">
          <span className="h-[60px] w-10 shrink-0 rounded bg-line" />
          <div className="flex-1">
            <div className="h-3.5 w-36 rounded bg-line-2" />
            <div className="mt-2 h-3 w-28 rounded bg-line" />
          </div>
          <span className="h-9 w-28 rounded-md bg-line" />
        </div>
      ))}
    </div>
  )
}
