import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { settingsQuery, useUpdateSettings, type ActivitySettings } from '../../api/settings'
import { playsOlderThanQuery } from '../../api/serverSettings'
import { Button, Card, HrefLink, HubPage, ItemRow, LoadingRows, Message, SaveNote, Switch, fieldInput } from '../../components/settings/Frame'
import { errorText, useInstantSave } from '../../components/settings/hooks'
import { isShortening, keepLabel, minPlayOptions, retentionLine, retentionOptions, retentionSaved, shorteningWarning } from '../../lib/historySettings'
import { settingsCrumbs, settingsHref } from '../../lib/settingsRoutes'
import { ServerOnly } from './SettingsLayout'

/**
 * Settings › Server › Watch history: five
 * choices that save at once and confirm in their row; shortening Keep
 * history for warns first with how many plays it removes.
 */
export function HistorySettings() {
  return (
    <ServerOnly tab="history">
      <HistoryHub />
    </ServerOnly>
  )
}

const LEDE = 'What the server keeps about plays. Changes apply to plays that end from now on.'

function HistoryHub() {
  const settings = useQuery(settingsQuery)
  const a = settings.data?.activity
  const crumbs = settingsCrumbs('history')
  return (
    <HubPage crumbs={crumbs} title="Watch history" lede={LEDE}>
      {!a ? (
        settings.isError ? <Message tone="err" title="Couldn’t load the watch history settings">{errorText(settings.error)}</Message> : <LoadingRows rows={5} />
      ) : (
        <>
          <Card>
            <MinPlayRow a={a} />
            <RetentionRow a={a} />
            <SwitchRow a={a} field="groupPlays" title="Count a resumed play as one" detail="Stopping and carrying on later is one play, not two." on="Resumed plays join the play they carry on." off="A resumed play is a play of its own." />
            <SwitchRow a={a} field="trimStats" title="Trim statistics along with history" detail={a.trimStats ? 'On: totals in Activity drop the plays that were removed.' : 'Off: totals in Activity keep counting plays that were removed.'} on="Totals in Activity drop the plays that are removed." off="Totals in Activity keep counting plays that are removed." />
            <SwitchRow a={a} field="household" title="Let everyone see what’s playing" detail="Members see Now playing without other people’s addresses. History and statistics stay private." on="Members see what’s playing now." off="Members see only their own plays." />
          </Card>
          <p className="text-sm text-neutral-400">
            Whose plays are recorded at all is set per person in <HrefLink href={settingsHref('users')} className="text-neutral-200 underline">Users</HrefLink>.
          </p>
        </>
      )}
    </HubPage>
  )
}

/** Saves the whole activity block with one field changed, as the server takes it. */
function useSaveActivity() {
  const update = useUpdateSettings()
  return (a: ActivitySettings, patch: Partial<ActivitySettings>) => update.mutateAsync({ activity: { ...a, ...patch } })
}

function MinPlayRow({ a }: { a: ActivitySettings }) {
  const save = useSaveActivity()
  const note = useInstantSave()
  return (
    <ItemRow
      title="Keep plays longer than"
      detail={note.state.status === 'idle' ? 'Shorter plays that weren’t finished aren’t kept.' : <SaveNote state={note.state} />}
      control={
        <select aria-label="Keep plays longer than" value={a.minPlaySeconds} disabled={note.state.status === 'saving'} className={`${fieldInput} min-w-[9rem]`}
          onChange={(e) => { const v = Number(e.target.value); void note.run(() => save(a, { minPlaySeconds: v }), v === 0 ? 'Every play is kept.' : 'Shorter unfinished plays aren’t kept from now on.') }}>
          {minPlayOptions(a.minPlaySeconds).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      }
    />
  )
}

/** Keep history for: a longer limit saves at once; a shorter one warns with the count first. */
function RetentionRow({ a }: { a: ActivitySettings }) {
  const save = useSaveActivity()
  const note = useInstantSave()
  const qc = useQueryClient()
  const [asking, setAsking] = useState<{ days: number; plays?: number } | null>(null)
  const [counting, setCounting] = useState(false)
  const commit = (days: number) => {
    setAsking(null)
    void note.run(() => save(a, { retentionDays: days }), retentionSaved(days))
  }
  const choose = async (days: number) => {
    note.reset()
    if (!isShortening(a.retentionDays, days)) { commit(days); return }
    setCounting(true)
    let plays: number | undefined
    try {
      plays = (await qc.fetchQuery(playsOlderThanQuery(days))).plays
    } catch {
      plays = undefined
    }
    setCounting(false)
    if (plays === 0) commit(days)
    else setAsking({ days, plays })
  }
  return (
    <ItemRow
      title="Keep history for"
      detail={counting ? 'Counting the plays this would remove…' : note.state.status === 'idle' ? retentionLine(a.retentionDays) : <SaveNote state={note.state} />}
      control={
        <select aria-label="Keep history for" value={asking?.days ?? a.retentionDays} disabled={note.state.status === 'saving' || counting} className={`${fieldInput} min-w-[9rem]`}
          onChange={(e) => void choose(Number(e.target.value))}>
          {retentionOptions(a.retentionDays).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      }
    >
      {asking && (
        <Message tone="warn" focus className="mt-2" actions={<>
          <Button small onClick={() => setAsking(null)}>{keepLabel(a.retentionDays)}</Button>
          <Button small variant="danger" onClick={() => commit(asking.days)}>Remove older plays</Button>
        </>}>
          {shorteningWarning(asking.days, asking.plays)}
        </Message>
      )}
    </ItemRow>
  )
}

function SwitchRow({ a, field, title, detail, on, off }: { a: ActivitySettings; field: 'groupPlays' | 'trimStats' | 'household'; title: string; detail: string; on: string; off: string }) {
  const save = useSaveActivity()
  const note = useInstantSave()
  return (
    <ItemRow
      title={title}
      detail={note.state.status === 'idle' ? detail : <SaveNote state={note.state} />}
      control={<Switch label={title} checked={a[field]} disabled={note.state.status === 'saving'} onChange={(next) => void note.run(() => save(a, { [field]: next }), next ? on : off)} />}
    />
  )
}
