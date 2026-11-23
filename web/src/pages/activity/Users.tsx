import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { activityUserQuery, activityUsersQuery, episodeLink, historyQuery, itemLink, itemTitle, liveActivityQuery, type StatItem, type UserSummary } from '../../api/activity'
import { liveTVActivityQuery, liveTVCapabilitiesQuery } from '../../api/queries'
import { useAuth } from '../../authContext'
import { Avatar } from '../../components/activity/StreamDetail'
import { tile } from '../../components/activity/styles'
import { ChevronLeftIcon, ChevronRightIcon } from '../../components/icons'
import { Poster } from '../../components/Poster'
import { historyResult, NETWORK_WORDS, watchedWords } from '../../lib/activityWords'
import { relativeTime } from '../../lib/format'

/** What each person is watching now, from Now playing's replies: a title, or a channel. */
function useWatchingNow() {
  const live = useQuery(liveActivityQuery)
  const caps = useQuery(liveTVCapabilitiesQuery)
  const liveTV = useQuery({ ...liveTVActivityQuery, enabled: !!caps.data?.enabled && !!caps.data.allowed })
  return (userId: number): { title: string; link?: ReturnType<typeof itemLink>; channelId?: string; where?: string } | undefined => {
    const play = live.data?.plays.find((p) => p.user.id === userId)
    if (play) return { title: itemTitle(play.item).title, link: itemLink(play.item), where: play.device.name }
    const session = liveTV.data?.items.find((s) => s.userId === userId)
    if (session) return { title: `Live TV · ${[session.channel.number, session.channel.name].filter(Boolean).join(' ')}`, channelId: session.channel.id, where: session.clientName }
    return undefined
  }
}

/** People (admins): a card for each person, with what they watch now, lately and most. */
export function Users() {
  const users = useQuery(activityUsersQuery)
  const watching = useWatchingNow()
  if (users.isPending) return <p className="mt-6 text-neutral-400">Loading…</p>
  if (users.isError) return <p className="mt-6 text-red-400">{users.error.message}</p>
  const items = users.data.items
  const now = items.filter((u) => u.playing > 0).length
  return (
    <div>
      <p className="mt-5 text-sm text-neutral-300">
        <b className="font-semibold text-neutral-100">{items.length === 1 ? '1 person' : `${items.length} people`}</b>
        {now > 0 && ` · ${now} watching now`}
      </p>
      <div className="mt-3.5 grid grid-cols-[repeat(auto-fit,minmax(min(300px,100%),1fr))] gap-4">
        {items.map((u) => (
          <PersonCard key={u.user.id} summary={u} now={watching(u.user.id)} />
        ))}
      </div>
    </div>
  )
}

function PersonCard({ summary: u, now }: { summary: UserSummary; now: ReturnType<ReturnType<typeof useWatchingNow>> }) {
  const auth = useAuth()
  const detail = useQuery(activityUserQuery(u.user.id))
  const month = detail.data?.windows.find((w) => w.days === 30)
  const top = [...(detail.data?.topShows ?? []), ...(detail.data?.topMovies ?? [])].sort((a, b) => b.playedMs - a.playedMs).slice(0, 3)
  const mostly = [...(detail.data?.deviceList ?? [])].sort((a, b) => b.plays - a.plays)[0]
  const role = [u.isAdmin ? 'Admin' : 'Member', u.user.id === auth.user?.id ? 'you' : ''].filter(Boolean).join(' · ')
  return (
    <article className="panel flex flex-col gap-3.5 p-[18px]">
      <Link to="/activity/users/$userId" params={{ userId: String(u.user.id) }} aria-label={`Open ${u.user.name}’s page`} className="group flex items-center gap-3.5">
        <Avatar name={u.user.name} size="lg" />
        <span className="min-w-0 flex-1">
          <b className="block truncate text-[17px] font-semibold group-hover:text-accent">{u.user.name}</b>
          <span className="text-[13px] text-neutral-400">{role}</span>
        </span>
        <ChevronRightIcon size={18} className="text-neutral-400" />
      </Link>
      <p className="flex min-h-[22px] items-center gap-2 text-[13.5px]">
        {now ? (
          <>
            <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-accent" />
            <span className="min-w-0 truncate">
              Watching now:{' '}
              {now.link ? (
                <Link {...now.link} className="font-semibold hover:text-accent">
                  {now.title}
                </Link>
              ) : now.channelId ? (
                <Link to="/live-tv/watch/$channelId" params={{ channelId: now.channelId }} className="font-semibold hover:text-accent">
                  {now.title}
                </Link>
              ) : (
                <b className="font-semibold">{now.title}</b>
              )}
            </span>
          </>
        ) : (
          <span className="text-neutral-400">{u.lastPlayed ? `Last watched ${relativeTime(u.lastPlayed.startedAt)}` : 'Hasn’t watched anything yet'}</span>
        )}
      </p>
      {!u.recordHistory && <p className="text-[13px] text-warn">History is off: plays show in Now playing but aren’t kept.</p>}
      <div>
        <p className="text-[12.5px] text-neutral-400">{month ? 'Last 30 days' : 'All time'}</p>
        <p className="mt-0.5 text-xl font-semibold tracking-[-.01em]">
          {month ? `${watchedWords(month.playedMs)} · ${month.plays} ${month.plays === 1 ? 'play' : 'plays'}` : `${watchedWords(u.playedMs)} · ${u.plays.toLocaleString()} ${u.plays === 1 ? 'play' : 'plays'}`}
        </p>
      </div>
      {top.length > 0 && (
        <div>
          <p className="text-[12.5px] text-neutral-400">Watches most</p>
          <ul className="mt-2 flex gap-2">
            {top.map((it) => (
              <li key={`${it.movieId ?? ''}-${it.showId ?? ''}`} className="w-16">
                <TitleLink item={it}>
                  <Poster path={it.posterPath} size="w92" alt={it.title} className="rounded-md" />
                </TitleLink>
              </li>
            ))}
          </ul>
        </div>
      )}
      <p className="mt-auto border-t border-line pt-3 text-[13px] text-neutral-400">
        {u.devices === 1 ? '1 device' : `${u.devices} devices`}
        {mostly ? ` · mostly ${mostly.device.name}` : ''}
      </p>
    </article>
  )
}

function TitleLink({ item, children, className = 'card block rounded-md' }: { item: StatItem; children: React.ReactNode; className?: string }) {
  if (item.movieId)
    return (
      <Link to="/movies/$movieId" params={{ movieId: String(item.movieId) }} aria-label={item.title} className={className}>
        {children}
      </Link>
    )
  if (item.showId)
    return (
      <Link to="/shows/$showId" params={{ showId: String(item.showId) }} aria-label={item.title} className={className}>
        {children}
      </Link>
    )
  return <span className={className}>{children}</span>
}

/** One person: how much they watch, lately, their devices, and their favourites. */
export function UserDetail({ userId }: { userId: number }) {
  const user = useQuery(activityUserQuery(userId))
  const recent = useInfiniteQuery(historyQuery({ user: userId }, 5))
  const watching = useWatchingNow()
  if (user.isPending) return <p className="mt-6 text-neutral-400">Loading…</p>
  if (user.isError) return <p className="mt-6 text-red-400">{user.error.message}</p>
  const u = user.data
  const rows = recent.data?.pages[0]?.items ?? []
  const now = watching(userId)
  const name = u.user.name
  const devices = [...u.deviceList].sort((a, b) => Date.parse(b.lastSeenAt) - Date.parse(a.lastSeenAt))
  return (
    <div>
      <Link to="/activity/users" className="btn btn-sm btn-ghost -ml-3 mt-4">
        <ChevronLeftIcon size={15} />
        People
      </Link>
      <header className="mt-2.5 flex flex-wrap items-center gap-x-[18px] gap-y-3.5">
        <Avatar name={name} size="xl" />
        <div className="min-w-0 flex-[1_1_240px]">
          <h2 className="text-2xl font-bold tracking-[-.02em]">{name}</h2>
          <p className="mt-1 flex flex-wrap items-center gap-2 text-[13.5px]">
            {now ? (
              <>
                <span aria-hidden="true" className="size-2 rounded-full bg-accent" />
                <span>
                  Watching now: <b className="font-semibold">{now.title}</b>
                </span>
                {now.where && <span className="text-neutral-400">· {now.where}</span>}
              </>
            ) : (
              <span className="text-neutral-400">{u.isAdmin ? 'Admin' : 'Member'}{u.lastSeenAt ? ` · last seen ${relativeTime(u.lastSeenAt)}` : ''}</span>
            )}
          </p>
        </div>
        <Link to="/activity/history" search={{ user: userId }} className="btn">
          Full history ›
        </Link>
        <Link to="/activity/stats" search={{ user: userId }} className="btn">
          Stats ›
        </Link>
      </header>
      {!u.recordHistory && <p className="mt-4 rounded-xl border border-warn/40 bg-warn/10 px-4 py-3 text-sm text-neutral-200">History is off for {name}: their plays show in Now playing but aren’t kept.</p>}

      <section aria-label={`How much ${name} watches`} className="mt-[22px] grid grid-cols-[repeat(auto-fit,minmax(min(220px,100%),1fr))] gap-3.5">
        {u.windows.map((w) => (
          <div key={w.label} className={tile}>
            <span className="text-[13px] text-neutral-400">{w.label}</span>
            <span className="text-2xl font-semibold leading-tight tracking-[-.02em]">{watchedWords(w.playedMs)}</span>
            <span className="text-[13px] text-neutral-300">
              {w.plays.toLocaleString()} {w.plays === 1 ? 'play' : 'plays'}
            </span>
          </div>
        ))}
      </section>

      <div className="mt-[30px] grid grid-cols-[repeat(auto-fit,minmax(min(480px,100%),1fr))] items-start gap-x-5 gap-y-4">
        <section aria-labelledby="h-late">
          <div className="rowh">
            <h2 id="h-late">Lately</h2>
            <Link to="/activity/history" search={{ user: userId }} className="more">
              Full history ›
            </Link>
          </div>
          {rows.length === 0 ? (
            <p className="mt-3.5 text-sm text-neutral-400">Nothing yet.</p>
          ) : (
            <ul className="panel mt-3.5 divide-y divide-line">
              {rows.map((r) => {
                const t = itemTitle(r.item)
                const link = itemLink(r.item)
                const episode = episodeLink(r.item)
                return (
                  <li key={r.id} className="flex flex-wrap items-center gap-x-3.5 gap-y-2 px-3.5 py-3">
                    <span className="w-[34px] shrink-0">
                      <Poster path={r.item.posterPath} size="w92" alt="" className="rounded" />
                    </span>
                    <span className="min-w-0 flex-[1_1_180px]">
                      <span className="block truncate text-sm font-medium">
                        {link ? (
                          <Link {...link} className="hover:text-accent">
                            {t.title}
                          </Link>
                        ) : (
                          t.title
                        )}
                      </span>
                      <span className="block truncate text-[12.5px] text-neutral-400">
                        {episode ? (
                          <Link {...episode} className="hover:text-accent">
                            {t.subtitle}
                          </Link>
                        ) : (
                          t.subtitle || 'Movie'
                        )}
                      </span>
                    </span>
                    <span className="flex flex-[0_1_230px] items-center gap-2.5 text-[13px] text-neutral-300">
                      <span className="meter w-12 shrink-0" aria-hidden="true">
                        <i style={{ width: `${r.completed ? 100 : r.percent}%` }} />
                      </span>
                      <span>
                        {relativeTime(r.startedAt)} · {historyResult(r)}
                      </span>
                    </span>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
        <section aria-labelledby="h-dev">
          <div className="rowh">
            <h2 id="h-dev">Devices</h2>
            <span className="note">{u.deviceList.length === 1 ? '1 device' : `${u.deviceList.length} devices`}</span>
          </div>
          {devices.length === 0 ? (
            <p className="mt-3.5 text-sm text-neutral-400">None yet.</p>
          ) : (
            <>
              <div className="panel mt-3.5 overflow-x-auto">
                <table className="tbl">
                  <thead>
                    <tr>
                      <th scope="col">Device</th>
                      <th scope="col">Last seen</th>
                      <th scope="col" className="text-right!">
                        Plays
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {devices.map((d, i) => {
                      const edge = i === devices.length - 1 ? 'border-b-0' : ''
                      return (
                        <tr key={d.device.id}>
                          <td className={edge}>
                            <b className="block font-semibold">{d.device.name}</b>
                            <span className="block text-[12.5px] text-neutral-400">{[d.device.product || d.device.platform, d.device.version ? `app ${d.device.version}` : ''].filter(Boolean).join(' · ')}</span>
                          </td>
                          <td className={`${edge} whitespace-nowrap`}>{relativeTime(d.lastSeenAt)}</td>
                          <td className={`${edge} text-right tabular-nums`}>{d.plays.toLocaleString()}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              {devices.some((d) => d.lastIp) && (
                <details className="tech mt-3">
                  <summary>
                    Addresses<span className="hint max-sm:hidden">Where each device last connected from</span>
                  </summary>
                  <div className="tech-body">
                    <dl className="dl text-[13px]">
                      {devices
                        .filter((d) => d.lastIp)
                        .map((d) => (
                          <div key={d.device.id} className="contents">
                            <dt>{d.device.name}</dt>
                            <dd className="font-mono text-xs">{d.lastIp}</dd>
                          </div>
                        ))}
                    </dl>
                    <p className="mt-3 text-xs text-neutral-400">Networks are named {Object.values(NETWORK_WORDS).join(', ').toLowerCase()}.</p>
                  </div>
                </details>
              )}
            </>
          )}
        </section>
      </div>

      <Favourites title="Favourite shows" note="By time watched" items={u.topShows} words={(it) => watchedWords(it.playedMs)} />
      <Favourites title="Favourite movies" note="By times watched" items={u.topMovies} words={(it) => (it.plays === 1 ? 'Watched once' : it.plays === 2 ? 'Watched twice' : `Watched ${it.plays} times`)} />
    </div>
  )
}

function Favourites({ title, note, items, words }: { title: string; note: string; items: StatItem[]; words: (it: StatItem) => string }) {
  if (!items.length) return null
  const id = `h-${title.toLowerCase().replace(/\W+/g, '-')}`
  return (
    <section aria-labelledby={id} className="mt-8">
      <div className="rowh">
        <h2 id={id}>{title}</h2>
        <span className="note">{note}</span>
      </div>
      <ul className="scrollbar-hide -mx-1 mt-3.5 flex gap-3 overflow-x-auto px-1 pb-1.5 pt-1 md:gap-4">
        {items.map((it) => (
          <li key={`${it.movieId ?? ''}-${it.showId ?? ''}`} className="w-[112px] shrink-0 md:w-[132px]">
            <TitleLink item={it} className="card block rounded-lg">
              <Poster path={it.posterPath} size="w185" alt="" />
              <span className="mt-2 block truncate text-sm font-medium">{it.title}</span>
              <span className="block truncate text-[12.5px] text-neutral-400">{words(it)}</span>
            </TitleLink>
          </li>
        ))}
      </ul>
    </section>
  )
}
