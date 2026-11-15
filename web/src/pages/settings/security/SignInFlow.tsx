import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { checkTurnstile, settingsQuery, useUpdateSettings } from '../../../api/settings'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { FlowFrame } from '../../../components/settings/Flow'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { Button, Card, Field, LoadingRows, Message, Outcome, Pill, fieldInput } from '../../../components/settings/Frame'
import { errorText, useGo } from '../../../components/settings/hooks'
import { Turnstile } from '../../../components/Turnstile'
import { TURNSTILE_STEPS, keysRefusal, resolveTurnstileStep, type KeysRefusal } from '../../../lib/securitySettings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { leavingFlow, noteTurnedOff, setFlowFlags, setLeavingFlow, useFlowFlags } from './store'
import { TurnOffProtectionDialog } from './TurnOffDialogs'

const crumbs = settingsCrumbs('security', { label: 'Turn on sign-in protection' })
const stepHref = (key: string) => settingsHref('security', 'sign-in-protection', key)
const hub = settingsHref('security')

/**
 * Turn on sign-in protection (§5.6): Get keys → Enter keys → Try it → On.
 * Enter keys saves (the server checks the secret with Cloudflare first, so a
 * wrong key can't lock anyone out). Try it comes while still signed in, as
 * the safeguard against a key that passes the check but fails for this address.
 */
export function SignInProtectionFlow({ step: requested }: { step?: string }) {
  const go = useGo()
  const settings = useQuery(settingsQuery)
  const flags = useFlowFlags()
  const ts = settings.data?.turnstile
  // While Try it turns protection off on the way back to the hub, it stays on screen.
  const holding = leavingFlow() ? TURNSTILE_STEPS.find((s) => s.key === requested) : undefined
  const step = ts ? holding ?? resolveTurnstileStep(requested, { hasKeys: !!ts.siteKey, enabled: ts.enabled, ...flags }) : null
  const offered = ts && !ts.fromConfig && !ts.disabled
  useEffect(() => {
    if (step && offered && step.key !== requested) go(stepHref(step.key), { replace: true })
  }, [go, offered, requested, step])

  if (!ts || !offered) {
    return (
      <FlowFrame tab="security" crumbs={crumbs} title={ts ? 'Sign-in protection is set in config.yaml' : 'Turn on sign-in protection'} next={{ label: 'Back to Security', href: hub }}>
        {settings.isPending ? <LoadingRows rows={2} />
          : settings.isError ? <Message tone="err" title="Couldn’t read the security settings">{errorText(settings.error)}</Message>
          : ts?.fromConfig ? <Message title="Set in config.yaml">The keys come from <code>auth.turnstile</code> in config.yaml or the environment, so they can’t be changed here. Change them there and restart the server.</Message>
          : <Message tone="warn" title="Turned off in config.yaml"><code>auth.turnstile.disabled</code> is set in config.yaml or the environment, so sign-in protection stays off whatever keys are saved. Remove it there and restart the server first.</Message>}
      </FlowFrame>
    )
  }
  if (step!.key === 'get') return <GetKeysStep />
  if (step!.key === 'keys') return <KeysStep siteKey={ts.siteKey ?? ''} secretHint={ts.secretKeyHint} />
  if (step!.key === 'try') return <TryStep siteKey={ts.siteKey ?? ''} />
  return <OnStep />
}

function GetKeysStep() {
  const go = useGo()
  const host = window.location.hostname
  return (
    <FlowFrame
      tab="security"
      crumbs={crumbs}
      steps={TURNSTILE_STEPS}
      current="get"
      stepHref={stepHref}
      title="Get two keys from Cloudflare"
      lede="Turnstile is free. You need a Cloudflare account and about three minutes."
      skip={{ label: 'Cancel', href: hub }}
      next={{ label: 'I have my keys', onClick: () => { setFlowFlags({ gotKeys: true }); go(stepHref('keys')) } }}
      help={<>
        <h4>Who sees it?</h4>
        <p>Browsers, on the sign-in and setup pages. The TV and phone apps pair with a code instead.</p>
        <h4>Home network only?</h4>
        <p>You don’t need this if the server can’t be reached from the internet.</p>
      </>}
    >
      <Card padded>
        <ol className="list-decimal space-y-2 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
          <li>Open <a href="https://dash.cloudflare.com/?to=/:account/turnstile" target="_blank" rel="noreferrer" className="text-neutral-100 underline">dash.cloudflare.com › Turnstile <span aria-hidden="true">↗</span><span className="sr-only">(opens in a new tab)</span></a> and choose Add widget.</li>
          <li>Add the address people use to reach this server, such as <code className="break-all text-neutral-100">{host}</code>.</li>
          <li>Choose Managed, then Create.</li>
          <li>Keep the page open: the next step asks for the site key and the secret key.</li>
        </ol>
      </Card>
    </FlowFrame>
  )
}

function KeysStep({ siteKey, secretHint }: { siteKey: string; secretHint?: string }) {
  const update = useUpdateSettings()
  const [site, setSite] = useState(siteKey)
  const [secret, setSecret] = useState('')
  const [refusal, setRefusal] = useState<KeysRefusal | null>(null)
  const [saved, setSaved] = useState(false)
  const dirty = !saved && (site !== siteKey || secret !== '')
  // A full load, so the page's Content-Security-Policy lets Cloudflare's
  // widget in now that protection is on. It runs once the leave check is off.
  useEffect(() => {
    if (saved) window.location.assign(stepHref('try'))
  }, [saved])
  const save = () => {
    if (!site.trim()) return setRefusal({ field: 'site', text: 'Enter the site key.' })
    if (!secret.trim() && !secretHint) return setRefusal({ field: 'secret', text: 'Enter the secret key too.' })
    setRefusal(null)
    update.mutate(
      { turnstile: { siteKey: site.trim(), secretKey: secret.trim() || undefined } },
      {
        onSuccess: () => { setFlowFlags({ tried: false }); setSaved(true) },
        onError: (error) => { if (visibleConfirmationError(error)) setRefusal(keysRefusal(error)) },
      },
    )
  }
  // A refusal with a title is the step's message, above the footer; one without is the field's.
  const fieldError = (f: 'site' | 'secret') => (refusal?.field === f && !refusal.title ? refusal.text : undefined)
  return (
    <FlowFrame
      tab="security"
      crumbs={crumbs}
      steps={TURNSTILE_STEPS}
      current="keys"
      stepHref={stepHref}
      title="Enter the keys"
      lede="The secret key is checked with Cloudflare before anything is saved, so a wrong key can’t lock anyone out."
      back={{ label: 'Get keys', href: stepHref('get') }}
      next={{ label: 'Check and save', guarded: true, busy: update.isPending || saved, busyLabel: saved ? 'Opening the check…' : 'Checking with Cloudflare…', onClick: save }}
    >
      <LeaveCheck when={dirty && !update.isPending} what="The keys you typed aren’t saved, and sign-in works as before." />
      <div className="max-w-xl">
        <Field label="Site key" error={fieldError('site')}>
          {(props) => <input {...props} value={site} onChange={(e) => { setSite(e.target.value); setRefusal(null) }} autoComplete="off" spellCheck={false} disabled={update.isPending} className={`${fieldInput} font-mono`} />}
        </Field>
        <Field label="Secret key" hint={secretHint ? `Leave it empty to keep the saved one, ending ${secretHint.replace(/^…/, '')}.` : undefined} error={fieldError('secret')}>
          {(props) => <input {...props} type="password" value={secret} onChange={(e) => { setSecret(e.target.value); setRefusal(null) }} autoComplete="off" spellCheck={false} disabled={update.isPending} className={`${fieldInput} font-mono`} />}
        </Field>
      </div>
      {refusal && refusal.title && (
        <Message tone={refusal.title === 'Set in config.yaml' ? 'info' : 'err'} title={refusal.title} className="mt-6 max-w-2xl" focus>{refusal.text}</Message>
      )}
    </FlowFrame>
  )
}

type CheckResult = { ok: true; message: string } | { ok: false; message: string; widget?: boolean }

function TryStep({ siteKey }: { siteKey: string }) {
  const go = useGo()
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<CheckResult | null>(null)
  const [turningOff, setTurningOff] = useState(false)
  const host = window.location.hostname
  const again = () => { setResult(null); setAttempt((n) => n + 1) }
  return (
    <FlowFrame
      tab="security"
      crumbs={crumbs}
      steps={TURNSTILE_STEPS}
      current="try"
      stepHref={stepHref}
      title="Try the check"
      lede="This is what people will see on the sign-in page. If it doesn’t turn green, turn protection off again before you sign out."
      skip={{ label: 'Turn it off again', onClick: () => setTurningOff(true) }}
      next={{
        label: 'It works, finish',
        disabled: !result?.ok,
        reason: 'Finish once the check turns green.',
        onClick: () => { setFlowFlags({ tried: true }); go(stepHref('on')) },
      }}
    >
      <Card padded className="max-w-sm">
        <Turnstile
          siteKey={siteKey}
          action="settings"
          resetKey={attempt}
          onToken={(token) => {
            if (!token) return
            void checkTurnstile(token)
              .then((r) => setResult(r.ok ? { ok: true, message: r.message } : { ok: false, message: r.message }))
              .catch((e: unknown) => setResult({ ok: false, message: errorText(e) }))
          }}
          onError={() => setResult({ ok: false, widget: true, message: '' })}
        />
        {result?.ok && <p className="mt-2 flex flex-wrap items-center gap-2 text-sm text-neutral-300" role="status"><Pill tone="ok">✓ Success</Pill> Cloudflare Turnstile</p>}
      </Card>
      {result && !result.ok && (
        <Message
          tone="err"
          className="mt-6 max-w-2xl"
          focus
          title={result.widget ? 'The check didn’t load' : 'Cloudflare didn’t accept the check'}
          actions={<Button small onClick={again}>Try again</Button>}
        >
          {result.widget
            ? <>The widget isn’t set up for <code>{host}</code>. Add that address to the widget in Cloudflare and try again, or turn protection off.</>
            : <>{result.message} Try again, or turn protection off.</>}
        </Message>
      )}
      {turningOff && (
        <TurnOffProtectionDialog
          onClose={() => setTurningOff(false)}
          onStart={() => setLeavingFlow(true)}
          onFailed={() => setLeavingFlow(false)}
          onDone={() => { noteTurnedOff(true); go(hub, { replace: true }) }}
        />
      )}
    </FlowFrame>
  )
}

function OnStep() {
  return (
    <FlowFrame
      tab="security"
      crumbs={crumbs}
      steps={TURNSTILE_STEPS}
      current="on"
      finished="ok"
      title="Sign-in protection is on"
      lede="Cloudflare Turnstile"
      next={{ label: 'Back to Security', href: hub }}
    >
      <Outcome
        changed={['The sign-in page shows the check before the password is sent.', 'The sidebar shows Security · On.']}
        next={[
          'People already signed in aren’t affected.',
          'The TV and phone apps pair with a code and never see the check.',
          <>If sign-in ever breaks, <code>auth.turnstile.disabled</code> in config.yaml turns it off.</>,
        ]}
      />
    </FlowFrame>
  )
}
