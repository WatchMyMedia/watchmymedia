import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { followsQuery, useRemoveFollow } from '../../../src/api/follows'
import { followLine, stopFailLine } from '../../../src/lib/newEpisodes'
import { useFocus, useNav } from '../../app/hooks'
import { ChoicePanel } from './Panels'
import type { PaneProps } from './SettingsScreen'
import { groupFocusId, paneRowId } from './settingsGroups'
import { PaneHeading, PaneNote, SettingRow } from './SettingRow'

const id = (row: string) => paneRowId('notifications', row)

/**
 * Settings › Notifications (SettingsGroups, Tweak `group`: `notifications`):
 * the shows this person follows, newest first, each with its newest
 * episode. OK opens Go to show and Stop notifying; stopping keeps the show
 * in the library and only its banners stop, on every device.
 */
export function NotificationsPane({ first }: PaneProps) {
  const nav = useNav()
  const focus = useFocus()
  const follows = useQuery(followsQuery)
  const [failed, setFailed] = useState<number>()
  const remove = useRemoveFollow({ onFailed: setFailed })
  const [open, setOpen] = useState<number>()
  const list = follows.data?.follows ?? []
  const show = list.find((f) => f.tmdbId === open)
  return <>
    <PaneNote>New episodes of the shows you follow show in a banner on this TV while WatchMyMedia is open. Follow a show from its page.</PaneNote>
    <PaneHeading>Shows you follow{follows.data ? ` · ${list.length}` : ''}</PaneHeading>
    {follows.isPending && <PaneNote>Loading…</PaneNote>}
    {follows.isError && <SettingRow id={id('failed')} label="Couldn’t load the shows you follow" detail="Check that the server is on, then try again." value="Try again" autoFocus={first} onPress={() => void follows.refetch()}/>}
    {follows.data && !list.length && <PaneNote>You don’t follow any shows yet. Choose Notify me on a show’s page.</PaneNote>}
    {list.map((f, i) => {
      const line = followLine(f)
      const error = failed === f.tmdbId ? stopFailLine(f.show?.title ?? f.title) : undefined
      return <SettingRow key={f.tmdbId} id={id(`show/${f.tmdbId}`)} label={f.show?.title ?? f.title} detail={error ?? line.text} stacked value="Stop notifying" chevron
        autoFocus={first && i === 0} onPress={() => { setFailed(undefined); setOpen(f.tmdbId) }}/>
    })}
    {!!list.length && <p className="settings-note settings-note--faint">Stop notifying keeps the show in your library; only its banners stop. The same on all your devices.</p>}
    {show && <ChoicePanel idPrefix="choice/follow" title={show.show?.title ?? show.title} context={`Shows you follow · ${followLine(show).text}`} onClose={() => setOpen(undefined)}
      choices={[...(show.show ? [{ value: 'open' as const, label: 'Go to show' }] : []), { value: 'stop' as const, label: 'Stop notifying' }]}
      onChoose={(v) => {
        if (v === 'open' && show.show) { nav.push({ name: 'show', id: show.show.id }); return }
        // Its row goes at once: focus moves to the next show, else the one before, before the panel closes.
        const at = list.indexOf(show)
        const next = list[at + 1] ?? list[at - 1]
        if (!(next && focus.focusId(id(`show/${next.tmdbId}`)))) focus.focusId(groupFocusId('notifications'))
        remove.mutate(show.tmdbId)
      }}/>}
  </>
}
