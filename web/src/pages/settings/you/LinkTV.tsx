import { useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRouterState } from '@tanstack/react-router'
import { approvePairing, pairingQuery, type PairingInfo } from '../../../api/account'
import { ApiError } from '../../../api/client'
import { useAuth } from '../../../authContext'
import { Button, Card, Field, LoadingRows, Message, Outcome, fieldInput } from '../../../components/settings/Frame'
import { FlowFrame, type FooterAction } from '../../../components/settings/Flow'
import { errorText, useGo } from '../../../components/settings/hooks'
import type { FlowStep } from '../../../lib/flowSteps'
import { APP_LINK, onAppPlatform, pairingKind } from '../../../lib/pairing'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { approveError, codeHint, isCode, linkHref, linkStep, lookupError, normalizeCode, type LinkProblem, type LinkStep } from '../../../lib/youSettings'

const STEPS: readonly FlowStep[] = [
  { key: 'code', label: 'Enter the code' },
  { key: 'check', label: 'Check it’s yours' },
  { key: 'done', label: 'Linked' },
]

// What approving a code gave back, so Linked can name the device after the
// address changes (the server forgets an approved code once the device has
// collected its token).
const approved = new Map<string, PairingInfo>()

/**
 * Link a TV (PLAN-ADMIN-SETTINGS.md §5.10): Enter the code → Check it's
 * yours → Linked, inside Settings, with Exit back to Account & devices. The
 * code is the address's step (/settings/account/link/ABC234), so /link from a
 * TV's QR code starts on the second step.
 */
export function LinkTVFlow({ params }: { params: Record<string, string> }) {
  const path = useRouterState({ select: (s) => s.location.pathname.replace(/\/+$/, '') })
  const go = useGo()
  const { step, code } = linkStep(params)
  const want = linkHref(step, code)
  useEffect(() => {
    if (want !== path) go(want, { replace: true })
  }, [want, path, go])
  if (step === 'code' || !code) return <CodeStep />
  return <CodeFlow key={code} code={code} step={step} />
}

function stepHref(code?: string) {
  return (key: string) => linkHref(key as LinkStep, code)
}

function Frame({ current, code, title, lede, help, back, skip, next, finished, children }: {
  current: LinkStep
  code?: string
  title: ReactNode
  lede?: ReactNode
  help?: ReactNode
  back?: FooterAction
  skip?: FooterAction
  next?: FooterAction
  finished?: 'ok'
  children?: ReactNode
}) {
  return (
    <FlowFrame tab="account" crumbs={settingsCrumbs('account', { label: 'Link a TV' })} steps={STEPS} current={current} stepHref={stepHref(code)}
      title={title} lede={lede} help={help} back={back} skip={skip} next={next} finished={finished}>
      {children}
    </FlowFrame>
  )
}

/** Step 1: the six letters and numbers the TV shows. */
function CodeStep({ initial = '', problem, onSame }: { initial?: string; problem?: LinkProblem; /** The code typed is the one already in the address: look again. */ onSame?: () => void }) {
  const go = useGo()
  const [typed, setTyped] = useState(initial)
  const code = normalizeCode(typed)
  const ready = isCode(code)
  return (
    <Frame
      current="code"
      title="Enter the code on your TV"
      lede="On the TV or in the WatchMyMedia app, choose Sign in with a code. It shows six letters and numbers."
      help={
        <>
          <h4>No code on the TV?</h4>
          <p>Open WatchMyMedia on it, choose this server, then Sign in with a code. A phone camera can open the QR code next to it instead.</p>
        </>
      }
      next={{ label: 'Find device', onClick: () => (onSame && code === initial ? onSame() : go(linkHref('check', code))), disabled: !ready }}
    >
      <div className="max-w-sm">
        <Field label="Code" hint={codeHint(typed)}>
          {(p) => (
            <input {...p} value={typed} onChange={(e) => setTyped(e.target.value)} placeholder="ABC234" maxLength={9} autoFocus
              autoCapitalize="characters" autoComplete="one-time-code" spellCheck={false}
              className={`${fieldInput} font-mono text-xl tracking-[0.3em] uppercase`} />
          )}
        </Field>
      </div>
      {problem && <Message tone="err" title={problem.title} focus className="mt-5 max-w-xl">{problem.body}</Message>}
    </Frame>
  )
}

function CodeFlow({ code, step }: { code: string; step: 'check' | 'done' }) {
  const auth = useAuth()
  const qc = useQueryClient()
  const go = useGo()
  const pairing = useQuery({ ...pairingQuery(code), enabled: step === 'check' || !approved.has(code) })
  const [problem, setProblem] = useState<LinkProblem | null>(null)
  const approve = useMutation({
    mutationFn: () => approvePairing(code),
    onSuccess: (device) => {
      approved.set(code, device)
      void qc.invalidateQueries({ queryKey: ['sessions'] })
      go(linkHref('done', code))
    },
    onError: (e) => {
      setProblem(approveError(e, code, errorText(e)))
      void qc.invalidateQueries({ queryKey: ['pairing', code] })
    },
  })
  const name = auth.user?.name ?? 'you'
  const done = approved.get(code) ?? (pairing.data?.approved ? pairing.data : undefined)

  // Linked without a device to name: the code is gone or wasn't approved here.
  const lost = step === 'done' && !done && (pairing.isError || (pairing.data && !pairing.data.approved))
  useEffect(() => {
    if (lost) go(linkHref('check', code), { replace: true })
  }, [lost, code, go])

  if (step === 'done' && done) return <Linked device={done} />

  if (pairing.isError && pairing.error instanceof ApiError && pairing.error.status === 404 && !problem) {
    return <CodeStep initial={code} problem={lookupError(pairing.error, code, errorText(pairing.error))} onSame={() => void pairing.refetch()} />
  }

  const device = pairing.data
  const kind = pairingKind(device?.platform)
  const noun = kind === 'tv' ? 'TV' : 'device'
  const already = device?.approved || problem?.kind === 'approved'
  const back: FooterAction = { label: 'Enter the code', href: linkHref('code') }
  let next: FooterAction | undefined
  if (already) next = { label: 'Back to Account & devices', href: settingsHref('account') }
  else if (problem?.kind === 'expired') next = { label: 'Enter a new code', href: linkHref('code') }
  else if (device) next = { label: `Sign in as ${name}`, onClick: () => { setProblem(null); approve.mutate() }, busy: approve.isPending, busyLabel: 'Signing it in…' }

  return (
    <Frame current="check" code={code} title={`Is this your ${noun}?`} lede={`It signs in as you, ${name}, and appears under Signed-in devices.`} back={back} next={next}>
      <div className="max-w-xl space-y-4">
        {device ? (
          <Card padded>
            <span className="text-[13px] text-neutral-400">{already ? 'Signed in' : 'Waiting to sign in'}</span>
            <h3 className="mt-1 text-lg font-semibold">{device.name || 'Unnamed device'}</h3>
            <p className="text-sm text-neutral-400">{[device.product, device.version, device.ip && `from ${device.ip}`, `code ${device.code}`].filter(Boolean).join(' · ')}</p>
          </Card>
        ) : pairing.isError ? (
          // After a refused approval its own message says what happened.
          !problem && (
            <Message tone="err" title={lookupError(pairing.error, code, errorText(pairing.error)).title} actions={<Button small onClick={() => void pairing.refetch()}>Try again</Button>}>
              {lookupError(pairing.error, code, errorText(pairing.error)).body}
            </Message>
          )
        ) : <LoadingRows rows={1} />}
        {already ? (
          <Message title="This device is already signed in">Someone approved {code} a moment ago. Carry on on the {noun === 'TV' ? 'TV' : 'device'}. It’s in your Signed-in devices.</Message>
        ) : device && (
          <Message tone="warn" title={`Only sign in a ${noun} you can see showing ${code}`}>Anyone with this code could be signed in as you.</Message>
        )}
        {problem && problem.kind !== 'approved' && <Message tone="err" title={problem.title} focus>{problem.body}</Message>}
      </div>
    </Frame>
  )
}

/** Step 3: the standard finished screen. */
function Linked({ device }: { device: PairingInfo }) {
  const auth = useAuth()
  const kind = pairingKind(device.platform)
  const label = device.name || (kind === 'tv' ? 'The TV' : 'The device')
  const here = kind === 'app' && onAppPlatform(device.platform, navigator.userAgent, navigator.maxTouchPoints)
  const nextItems: ReactNode[] = kind === 'app'
    ? [
        here ? (
          <>Go back to the WatchMyMedia app to carry on. This page is the web version, in your browser. <a href={APP_LINK} className="ml-1 inline-flex min-h-[36px] items-center rounded-md btn-primary px-3 font-semibold">Back to the app</a></>
        ) : `Carry on in the WatchMyMedia app on ${device.name || 'that device'}.`,
        'It stays signed in until it goes a year without being used.',
        'Sign it out any time from Account & devices.',
      ]
    : [
        `Carry on on the ${kind === 'tv' ? 'TV' : 'device'}. It opens Home by itself.`,
        'It stays signed in until it goes a year without being used.',
        'Sign it out any time from Account & devices.',
      ]
  return (
    <Frame
      current="done"
      code={device.code}
      finished="ok"
      title={`${label} is signed in`}
      lede={`It’s signing in as ${auth.user?.name ?? 'you'} now.`}
      skip={{ label: 'Link another', href: linkHref('code') }}
      next={{ label: 'Back to Account & devices', href: settingsHref('account') }}
    >
      <Outcome changed={[`${label} is signed in as you.`, 'It’s listed under Signed-in devices.']} next={nextItems} />
    </Frame>
  )
}
