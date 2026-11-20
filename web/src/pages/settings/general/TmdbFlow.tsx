import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, testTMDB, useUpdateSettings } from '../../../api/settings'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { FlowFrame } from '../../../components/settings/Flow'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { Button, Field, Message, fieldInput } from '../../../components/settings/Frame'
import { errorText, useGo } from '../../../components/settings/hooks'
import { tmdbDoneLine, tmdbSaveMessage, tmdbTestMessage, type KeyMessage } from '../../../lib/generalSettings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { leaveNotice } from './notice'

const hub = settingsHref('general')

/** Add a TMDB key: one step. Test saves nothing; Save checks the key with TMDB first. */
export function TmdbFlow() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const go = useGo()
  const [key, setKey] = useState('')
  const [tested, setTested] = useState<KeyMessage | null>(null)
  const [testing, setTesting] = useState(false)
  const replacing = !!settings.data?.tmdbConfigured
  const title = replacing ? 'Replace the TMDB key' : 'Add a TMDB key'
  const trimmed = key.trim()
  const test = () => {
    if (!trimmed) return
    setTesting(true)
    testTMDB(trimmed)
      .then((r) => setTested(tmdbTestMessage(r)))
      .catch((e: unknown) => setTested({ tone: 'err', title: 'Couldn’t test the key', body: errorText(e) }))
      .finally(() => setTesting(false))
  }
  const save = () => {
    if (!trimmed) return
    update.mutate({ tmdbApiKey: trimmed }, {
      onSuccess: () => {
        leaveNotice(tmdbDoneLine('saved'))
        go(hub)
      },
    })
  }
  const refused = update.isError && visibleConfirmationError(update.error) ? tmdbSaveMessage(update.error, errorText) : null
  return (
    <FlowFrame
      tab="general"
      crumbs={settingsCrumbs('general', { label: title })}
      title={title}
      lede="TMDB supplies titles, posters, cast and trailers."
      back={{ label: 'Cancel', href: hub }}
      next={{ label: 'Save key', onClick: save, busy: update.isPending, busyLabel: 'Checking with TMDB…', disabled: !trimmed, reason: !trimmed ? 'Paste a key first.' : undefined }}
      help={
        <>
          <h4>Getting a key</h4>
          <p>
            Make a free account at <a href="https://www.themoviedb.org/signup" target="_blank" rel="noreferrer" className="underline">themoviedb.org</a>, then{' '}
            <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noreferrer" className="underline">Settings › API</a> › Create. Choose Developer and personal use.
          </p>
          {replacing && <p>The key in use stays until the new one is saved.</p>}
        </>
      }
    >
      <LeaveCheck when={!!trimmed && !update.isPending} what="The key you pasted isn’t saved." />
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-0 flex-1 basis-72">
          <Field label="TMDB API key" hint="The short “API Key”, not the long “Read Access Token”.">
            {(p) => <input {...p} value={key} onChange={(e) => { setKey(e.target.value); setTested(null); update.reset() }} autoComplete="off" spellCheck={false} className={`${fieldInput} font-mono`} />}
          </Field>
        </div>
        <Button onClick={test} disabled={!trimmed} busy={testing} busyLabel="Testing…">Test</Button>
      </div>
      {tested && !refused && <Message tone={tested.tone} title={tested.title} className="mt-4">{tested.body}</Message>}
      {refused && <Message tone="err" focus title={refused.title} className="mt-4">{refused.body}</Message>}
    </FlowFrame>
  )
}
