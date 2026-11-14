import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { serverQuery } from '../../../api/queries'
import { settingsQuery, useUpdateSettings } from '../../../api/settings'
import type { components } from '../../../api/schema'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { FlowFrame } from '../../../components/settings/Flow'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { Button, Card, Choice, Field, LoadingRows, Message, Outcome, Spinner, fieldInput } from '../../../components/settings/Frame'
import { errorText, useGo } from '../../../components/settings/hooks'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { adapterChoices, adapterIndex, adapterIndexHint, adapterNamespace, adapterPreference } from '../../../lib/transcoder'
import {
  ENCODER_STEPS, encoderChoices, encoderLabel, encoderStep, encoderTest, failureAdvice, gpuName, inUseWords, takesGpu, testFailure, type Backend,
} from '../../../lib/transcodingSettings'
import { hardwarePoll } from './shared'

type Settings = components['schemas']['Settings']

const crumbs = settingsCrumbs('transcoding', { label: 'Choose an encoder' })
const stepHref = (key: string) => settingsHref('transcoding', 'encoder', key)
const hub = settingsHref('transcoding')

/** Choose an encoder: Choose → Test → In use (boards tx-enc-1, tx-enc-2, tx-enc-done). */
export function EncoderFlow({ step }: { step?: string }) {
  const settings = useQuery({ ...settingsQuery, refetchInterval: hardwarePoll })
  const server = useQuery(serverQuery)
  const go = useGo()
  const s = settings.data
  const test = encoderTest(s?.hwaccel ?? 'auto', s?.capabilities)
  const current = encoderStep(step, test)
  // An address whose step isn't open yet goes to the one that is (§4).
  useEffect(() => {
    if (s && step !== current.key) go(stepHref(current.key), { replace: true })
  }, [s, step, current.key, go])

  if (!s) {
    return (
      <FlowFrame tab="transcoding" crumbs={crumbs} steps={ENCODER_STEPS} current="choose" title="Choose an encoder" skip={{ label: 'Cancel', href: hub }}>
        {settings.isError ? <Message tone="err" title="Couldn’t load the transcoding settings">{errorText(settings.error)}</Message> : <LoadingRows rows={4} />}
      </FlowFrame>
    )
  }
  const platform = server.data?.platform
  if (current.key === 'choose') return <ChooseStep s={s} platform={platform} />
  if (current.key === 'test') return <TestStep s={s} platform={platform} />
  return <InUseStep s={s} platform={platform} />
}

function ChooseStep({ s, platform }: { s: Settings; platform?: string }) {
  const update = useUpdateSettings()
  const go = useGo()
  const [backend, setBackend] = useState<Backend>(s.hwaccel)
  const [device, setDevice] = useState(s.hwDevice === undefined || s.hwDevice < 0 ? '' : String(s.hwDevice))
  const [manual, setManual] = useState(false)
  const [tried, setTried] = useState(false)
  const { choices, notHere } = encoderChoices(platform, s.hwaccel)
  const gpu = takesGpu(backend, platform)
  const namespace = adapterNamespace(backend, platform)
  const named = adapterChoices(backend, platform, s.hardwareAdapters)
  const index = adapterIndex(device)
  const deviceError = gpu && index === undefined ? 'Enter a whole number from 0, or leave it empty for the default GPU.' : undefined
  const savedDevice = s.hwDevice === undefined || s.hwDevice < 0 ? '' : String(s.hwDevice)
  const changed = backend !== s.hwaccel || (gpu && device.trim() !== savedDevice)
  const showIndex = gpu && (named.length === 0 || manual || (device.trim() !== '' && !named.some((a) => String(a.index) === device.trim())))

  const testIt = () => {
    setTried(true)
    if (deviceError) return
    const body = gpu ? adapterPreference(backend, device) : { hwaccel: backend, hwDevice: -1 }
    if (!body) return
    update.mutate(body, { onSuccess: () => go(stepHref('test')) })
  }
  const label = encoderLabel(backend)
  return (
    <FlowFrame
      tab="transcoding"
      crumbs={crumbs}
      steps={ENCODER_STEPS}
      current="choose"
      stepHref={stepHref}
      title="Choose an encoder"
      lede="The choice is saved, then tested with a short encode. New plays use it once it passes."
      skip={{ label: 'Cancel', href: hub }}
      next={{ label: `Test ${label}`, onClick: testIt, busy: update.isPending, busyLabel: 'Saving…' }}
    >
      <LeaveCheck when={!!changed && !update.isPending && !update.isSuccess} what="The encoder you chose isn’t saved. The one in use stays." />
      <fieldset>
        <legend className="sr-only">Encoder</legend>
        {choices.map((c) => (
          <div key={c.backend} className="mt-2.5 first:mt-0">
            <Choice name="encoder" value={c.backend} checked={backend === c.backend} onChange={(on) => { if (on) { setBackend(c.backend); setManual(false); setDevice(c.backend === s.hwaccel ? savedDevice : '') } }}
              title={<>{c.label}{c.recommended && <span className="ml-2 inline-flex rounded-full border border-emerald-900 bg-emerald-950/60 px-2 py-0.5 text-xs font-medium text-emerald-300">Recommended</span>}</>}>
              {c.description}
            </Choice>
            {backend === c.backend && gpu && (
              <div className="mt-2 ml-8 max-w-md rounded-[10px] border border-line bg-surface p-3.5">
                {named.length > 0 && (
                  <Field label="GPU" hint={s.hardwareAdapters?.checking ? 'Checking the GPU names…' : undefined}>
                    {(p) => (
                      <select {...p} value={showIndex ? 'manual' : device.trim()} onChange={(e) => { if (e.target.value === 'manual') setManual(true); else { setManual(false); setDevice(e.target.value) } }} className={fieldInput}>
                        <option value="">Default GPU</option>
                        {named.map((a) => <option key={a.index} value={String(a.index)}>{a.name}</option>)}
                        <option value="manual">Enter a number…</option>
                      </select>
                    )}
                  </Field>
                )}
                {showIndex && namespace && (
                  <Field label={named.length ? `${namespace} number` : 'GPU'} hint={adapterIndexHint(namespace)} error={tried ? deviceError : undefined}>
                    {(p) => <input {...p} inputMode="numeric" value={device} placeholder="Default" onChange={(e) => setDevice(e.target.value)} className={`${fieldInput} max-w-[10rem]`} />}
                  </Field>
                )}
              </div>
            )}
          </div>
        ))}
      </fieldset>
      {notHere && <p className="mt-3 text-[13px] text-neutral-400">{notHere}</p>}
      {update.isError && visibleConfirmationError(update.error) && <Message tone="err" focus className="mt-4" title="The choice wasn’t saved">{errorText(update.error)}</Message>}
    </FlowFrame>
  )
}

function TestStep({ s, platform }: { s: Settings; platform?: string }) {
  const update = useUpdateSettings()
  const go = useGo()
  const test = encoderTest(s.hwaccel, s.capabilities)
  const label = encoderLabel(s.hwaccel)
  const gpu = gpuName(s.hwaccel, s.hwDevice, platform, s.hardwareAdapters)
  useEffect(() => {
    if (test === 'passed') go(stepHref('in-use'), { replace: true })
  }, [test, go])
  const testAgain = () => update.mutate({ hwaccel: s.hwaccel, hwDevice: s.hwDevice ?? -1 })
  const failure = test === 'failed' ? testFailure(s.hwaccel, s.hwDevice, s.hardwareDiagnostics) : null
  const busy = update.isPending || test === 'testing'
  return (
    <FlowFrame
      tab="transcoding"
      crumbs={crumbs}
      steps={ENCODER_STEPS}
      current="test"
      stepHref={stepHref}
      title={s.hwaccel === 'auto' ? 'Testing the encoders' : `Testing ${label}${gpu ? ` on ${gpu}` : ''}`}
      lede={s.hwaccel === 'software' ? 'The choice is saved.' : 'The choice is saved. Until it passes, the server uses software.'}
      back={{ label: 'Choose another', href: stepHref('choose') }}
      skip={s.hwaccel === 'software' ? undefined : { label: 'Use software for now', onClick: () => update.mutate({ hwaccel: 'software', hwDevice: -1 }), disabled: update.isPending }}
    >
      {busy && (
        <Card padded>
          <h3 className="flex items-center gap-2 font-semibold"><Spinner />Converting a few seconds of test video</h3>
          <p className="mt-1 text-sm text-neutral-400">This takes under a minute. Plays already running keep the encoder they started with. You can leave this page; the test carries on.</p>
        </Card>
      )}
      {!busy && failure?.kind === 'gpu-missing' && (
        <Message tone="err" focus title="That GPU wasn’t found" actions={<Button small onClick={() => go(stepHref('choose'))}>Choose another GPU</Button>}>
          {gpu ? `${gpu} isn’t there any more.` : `GPU ${s.hwDevice} isn’t there.`} Go back and choose another GPU.{failure.detail && <> The server said: <code>{failure.detail}</code></>}
        </Message>
      )}
      {!busy && failure?.kind === 'failed' && (
        <Message tone="err" focus title={`${label} didn’t pass the test`} actions={<Button small onClick={testAgain}>Test again</Button>}>
          {failure.detail && <>ffmpeg said: <code className="[overflow-wrap:anywhere]">{failure.detail}</code>. </>}
          {failureAdvice(s.hwaccel)} Until it passes, the server uses software.
        </Message>
      )}
      {update.isError && visibleConfirmationError(update.error) && <Message tone="err" focus className="mt-4" title="Couldn’t start the test">{errorText(update.error)}</Message>}
    </FlowFrame>
  )
}

function InUseStep({ s, platform }: { s: Settings; platform?: string }) {
  const caps = s.capabilities!
  const words = inUseWords(caps, gpuName(caps.hwaccel, caps.device, platform, s.hardwareAdapters))
  return (
    <FlowFrame
      tab="transcoding"
      crumbs={crumbs}
      steps={ENCODER_STEPS}
      current="in-use"
      finished="ok"
      title={words.title}
      lede={words.lede}
      next={{ label: 'Back to Transcoding', href: hub }}
    >
      <Outcome changed={words.changed} next={words.next} />
    </FlowFrame>
  )
}
