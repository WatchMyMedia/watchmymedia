import { useId } from 'react'
import { useQuery } from '@tanstack/react-query'
import { healthQuery, librariesQuery } from '../../../api/queries'
import { settingsQuery, useUpdateSettings } from '../../../api/settings'
import { useEventState } from '../../../api/events'
import { Card, HubPage, ItemRow, LoadingRows, Message, NextStep, Pill, SaveNote, Section, fieldInput } from '../../../components/settings/Frame'
import { useInstantSave } from '../../../components/settings/hooks'
import { fileCount, libraryState, mostUnmatched, nextScanIn, scanIntervalOptions } from '../../../lib/librariesSettings'
import { relativeTime } from '../../../lib/format'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { LinkButton, MatchingOff } from './parts'
import { useFlash } from './flash'

/** The Libraries hub: a row per library with its state in
 * words, Needs you when files are unmatched, and the scan schedule. */
export function LibrariesHub() {
  const libraries = useQuery(librariesQuery)
  const health = useQuery(healthQuery)
  const live = useEventState()
  const [flash, dismiss] = useFlash(settingsHref('libraries'))
  const list = libraries.data?.items ?? []
  const needs = mostUnmatched(list)
  const tmdbOff = health.data ? !health.data.tmdbConfigured : false

  const banner = (
    <>
      {flash && <Message tone={flash.tone} title={flash.title} actions={<button type="button" onClick={dismiss} className="min-h-[32px] text-sm text-neutral-400 underline hover:text-neutral-100">Dismiss</button>}>{flash.text}</Message>}
      {libraries.isError && <Message tone="err" title="Couldn’t load the libraries">The server didn’t answer. Reload the page to try again.</Message>}
      {tmdbOff && list.length > 0 && <MatchingOff />}
    </>
  )

  return (
    <HubPage
      crumbs={settingsCrumbs('libraries')}
      title="Libraries"
      lede="Folders on the server that hold your movies and shows."
      actions={<LinkButton href={settingsHref('libraries', 'add')} small>Add a library</LinkButton>}
      banner={flash || libraries.isError || (tmdbOff && list.length > 0) ? banner : undefined}
    >
      {needs && (
        <NextStep
          title={`${needs.unmatchedCount.toLocaleString()} file${needs.unmatchedCount === 1 ? '' : 's'} in ${needs.name} couldn’t be matched`}
          action={<LinkButton variant="primary" href={settingsHref('libraries', needs.id, 'unmatched')}>Review {needs.unmatchedCount.toLocaleString()} unmatched</LinkButton>}
        >
          They play, but have no poster or {needs.type === 'tv' ? 'episode' : 'movie'} details until you say what they are.
        </NextStep>
      )}
      {libraries.data && list.length === 0 && (
        <NextStep title="Add your first library" action={<LinkButton variant="primary" href={settingsHref('libraries', 'add')}>Add a library</LinkButton>}>
          Choose a folder on the server that holds your movies or shows. It’s scanned and matched to TMDB as soon as it’s added.
        </NextStep>
      )}
      {libraries.isPending ? (
        <LoadingRows rows={2} />
      ) : list.length > 0 && (
        <Card>
          {list.map((lib) => {
            const state = libraryState(lib, { scan: live.scans[lib.id], match: live.matches[lib.id], lastScan: live.lastScan[lib.id] })
            return (
              <ItemRow
                key={lib.id}
                href={settingsHref('libraries', lib.id)}
                title={lib.name}
                pill={<Pill tone={state.tone}>{state.label}</Pill>}
                detail={[lib.path, fileCount(lib.fileCount), lib.missingCount > 0 ? `${lib.missingCount.toLocaleString()} missing` : '', lib.lastScannedAt ? `scanned ${relativeTime(lib.lastScannedAt)}` : ''].filter(Boolean).join(' · ')}
              />
            )
          })}
        </Card>
      )}
      <ScanSchedule />
    </HubPage>
  )
}

/** How often every library is read in full: a choice, so it saves at once. */
function ScanSchedule() {
  const id = useId()
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const save = useInstantSave()
  const value = settings.data?.scanIntervalMinutes
  return (
    <Section title="Scanning">
      <Card>
        <ItemRow
          title={<label htmlFor={id}>Scan every library</label>}
          detail="Changes in the folders are also noticed within a minute. This scan finds the rest: network shares written to by other computers, and folders past the system’s limit on watches. Each scan reads every folder, which can wake sleeping drives."
          control={
            <select
              id={id}
              value={value ?? 15}
              disabled={!settings.data || save.state.status === 'saving'}
              onChange={(e) => {
                const minutes = Number(e.target.value)
                void save.run(() => update.mutateAsync({ scanIntervalMinutes: minutes }), `The next scan is in ${nextScanIn(minutes)}.`)
              }}
              className={`${fieldInput} min-w-[11rem] px-2 sm:w-auto`}
            >
              {scanIntervalOptions(value).map(([m, l]) => <option key={m} value={m}>{l}</option>)}
            </select>
          }
        >
          <div className="mt-1.5"><SaveNote state={save.state} /></div>
        </ItemRow>
      </Card>
    </Section>
  )
}
