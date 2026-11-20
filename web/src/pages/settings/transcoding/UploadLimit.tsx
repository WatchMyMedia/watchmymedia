import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, useUpdateSettings } from '../../../api/settings'
import type { components } from '../../../api/schema'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { Button, Card, Choice, Field, HubPage, ItemRow, LoadingRows, Message, SaveBar, Section, fieldInput } from '../../../components/settings/Frame'
import { errorText } from '../../../components/settings/hooks'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { mbps, uploadSaveError, uploadSavedLine, uploadTitle, uploadWarning } from '../../../lib/transcodingSettings'
import { parseUploadMbps, uploadMbps } from '../../../lib/uploadSettings'

type Snapshot = components['schemas']['Snapshot']

const crumbs = settingsCrumbs('transcoding', { label: 'Remote upload limit' })
const LEDE = 'How much of your internet upload the server may use for people away from home.'

/** Remote upload limit: a draft with the save bar; Save asks for the password away from home. */
export function UploadLimitPage() {
  const settings = useQuery({ ...settingsQuery, refetchInterval: 15_000 })
  const upload = settings.data?.upload
  if (!upload) {
    return (
      <HubPage crumbs={crumbs} title="Remote upload limit" lede={LEDE}>
        {settings.isError ? <Message tone="err" title="Couldn’t load the upload limit">{errorText(settings.error)}</Message>
          : settings.data ? <Message tone="info" title="This server doesn’t have an upload limit">Update the server to set one.</Message>
          : <LoadingRows rows={2} />}
      </HubPage>
    )
  }
  return <UploadDraft upload={upload} />
}

type Draft = { mbps: string; enforce: boolean; base: number }

function UploadDraft({ upload }: { upload: Snapshot }) {
  const update = useUpdateSettings()
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const revision = upload.policyRevision ?? 0
  const shown = draft ?? { mbps: uploadMbps(upload.configuredBps), enforce: upload.enforcing, base: revision }
  const bps = parseUploadMbps(shown.mbps)
  const dirty = !!draft && (bps !== upload.configuredBps || draft.enforce !== upload.enforcing)
  // Another browser saved while this draft was open: say so before Save, not after.
  const movedUnder = dirty && draft!.base !== revision
  const edit = (patch: Partial<Draft>) => {
    setSaved(null)
    setDraft({ ...shown, ...patch, base: draft?.base ?? revision })
  }
  const discard = () => { setDraft(null); setConflict(false); update.reset() }
  const save = (base = shown.base) => {
    if (bps === undefined) return
    setConflict(false)
    update.mutate({ upload: { bps, enforce: shown.enforce, expectedPolicyRevision: base } }, {
      onSuccess: () => { setDraft(null); setSaved(uploadSavedLine(bps, shown.enforce)) },
      onError: (error) => { if (uploadSaveError(error, errorText).conflict) setConflict(true) },
    })
  }
  const error = update.isError && visibleConfirmationError(update.error) ? uploadSaveError(update.error, errorText) : null
  const warning = dirty ? uploadWarning(upload, bps, shown.enforce) : null
  const showConflict = conflict || movedUnder
  return (
    <HubPage crumbs={crumbs} title="Remote upload limit" lede={LEDE} banner={saved ? <Message tone="ok" title="Remote upload limit saved">{saved}</Message> : undefined}>
      <LeaveCheck when={dirty} what="Your change to the upload limit isn’t saved." />
      <SaveBar dirty={dirty} guarded saving={update.isPending} onDiscard={discard} onSave={() => save()} />
      <Section>
        <div className="max-w-md">
          <Field label="Limit" hint="Streams and downloads away from home share it. 0 means no limit; streams at home don’t count." error={bps === undefined ? 'Enter 0 to 1,000,000 Mbps, with up to three decimal places.' : undefined}>
            {(p) => (
              <div className="flex items-center gap-2">
                <input {...p} inputMode="decimal" value={shown.mbps} onChange={(e) => edit({ mbps: e.target.value })} className={`${fieldInput} max-w-[10rem] tabular-nums`} />
                <span className="text-sm text-neutral-400">Mbps</span>
              </div>
            )}
          </Field>
        </div>
        <div className="mt-4 max-w-2xl">
          <Choice name="enforce" type="checkbox" checked={shown.enforce} onChange={(on) => edit({ enforce: on })} title="Enforce the limit">
            Off: the server only measures. On: streams are paced to fit, quality steps down, and a play that can’t fit is refused.
          </Choice>
        </div>
      </Section>
      {warning && <Message tone="warn" title={warning.title}>{warning.body}</Message>}
      {showConflict && (
        <Message tone="err" focus title="Someone else changed this while you were here" actions={<>
          <Button small onClick={discard}>Use the latest values</Button>
          <Button small guarded busy={update.isPending} onClick={() => save(revision)}>Save mine anyway</Button>
        </>}>
          It’s now {uploadTitle(upload)}. Your change wasn’t saved.
        </Message>
      )}
      {error && !error.conflict && <Message tone="err" focus title="The upload limit wasn’t saved">{error.message}</Message>}
      <Section title="Now">
        <Card>
          <ItemRow title="Reserved for plays" detail={`${upload.reservedBps > 0 ? mbps(upload.reservedBps) : 'None'} · ${upload.activeViews} remote ${upload.activeViews === 1 ? 'stream' : 'streams'}`} />
          <ItemRow title="Delivered recently" detail={`${mbps(upload.deliveredBps)} · the last 30 seconds on average`} />
        </Card>
      </Section>
    </HubPage>
  )
}
