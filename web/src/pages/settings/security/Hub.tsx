import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, useUpdateSettings } from '../../../api/settings'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { Button, Card, HubPage, ItemRow, LoadingRows, Message, Pill, SaveNote, Section, Switch } from '../../../components/settings/Frame'
import { errorText, useAway, useGo, useInstantSave } from '../../../components/settings/hooks'
import { remoteAdminRefusal, whereYouAre } from '../../../lib/securitySettings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { noteTurnedOff, setLeavingFlow, useTurnedOffNotice } from './store'
import { TurnOffProtectionDialog, TurnOffRemoteAdminDialog } from './TurnOffDialogs'

const flowHref = (step: string) => settingsHref('security', 'sign-in-protection', step)

/**
 * Settings › Security: where this browser is and what that means,
 * then sign-in protection and admin settings away from home, with the
 * complete list of what asks for the password away from home.
 */
export function SecurityHub() {
  const settings = useQuery(settingsQuery)
  const turnedOff = useTurnedOffNotice()
  useEffect(() => { setLeavingFlow(false) }, [])
  return (
    <HubPage
      crumbs={settingsCrumbs('security')}
      title="Security"
      lede="Who can sign in, and where admins can change the server’s settings from."
      banner={turnedOff && (
        <Message tone="ok" title="Sign-in protection is off" actions={<Button small variant="quiet" onClick={() => noteTurnedOff(false)}>Dismiss</Button>}>
          The sign-in page no longer shows the Cloudflare check, and TV and phone apps can sign in with a password again.
        </Message>
      )}
    >
      <WhereYouAre />
      {settings.isPending ? (
        <LoadingRows rows={2} />
      ) : settings.isError ? (
        <Message tone="err" title="Couldn’t read the security settings" actions={<Button small onClick={() => void settings.refetch()}>Try again</Button>}>
          {errorText(settings.error)}
        </Message>
      ) : (
        <>
          <Section title="Sign-in protection"><SignInProtection /></Section>
          <Section title="Admin settings away from home"><RemoteAdmin /></Section>
        </>
      )}
    </HubPage>
  )
}

/** The first card answers "why was I asked for my password?" before it is asked. */
function WhereYouAre() {
  const { away, minutes } = useAway()
  const words = whereYouAre({ away, minutes })
  return (
    <Card padded>
      <h3 className="font-semibold">Where you are now</h3>
      <p className="mt-1.5 text-sm text-neutral-400">
        This browser is <b className={away ? 'text-amber-300' : 'text-neutral-100'}>{words.where}</b>, {words.text}
      </p>
    </Card>
  )
}

function SignInProtection() {
  const go = useGo()
  const settings = useQuery(settingsQuery)
  const ts = settings.data!.turnstile
  const [turningOff, setTurningOff] = useState(false)
  const open = (step: string) => { noteTurnedOff(false); go(flowHref(step)) }
  const pill = ts.disabled ? <Pill tone="warn">Off in config</Pill> : ts.enabled ? <Pill tone="ok">On</Pill> : <Pill>Off</Pill>
  const control = ts.fromConfig ? null : ts.enabled ? (
    <>
      <Button small onClick={() => open('try')}>Try it</Button>
      <Button small guarded onClick={() => open('keys')}>Change keys</Button>
      <Button small variant="danger" guarded onClick={() => setTurningOff(true)}>Turn off</Button>
    </>
  ) : ts.disabled ? (
    ts.siteKey ? <Button small variant="danger" guarded onClick={() => setTurningOff(true)}>Remove keys</Button> : null
  ) : (
    <Button small guarded onClick={() => open('get')}>Turn on</Button>
  )
  return (
    <Card>
      <ItemRow
        title="Cloudflare Turnstile"
        pill={pill}
        detail={<>
          A check on the sign-in page that keeps scripts from guessing passwords. Worth having if this server can be reached from the internet.
          {ts.enabled && ' While it’s on, TV and phone apps sign in by linking with a code rather than a password.'}
        </>}
        control={control}
      />
      {ts.fromConfig && (
        <div className="px-5 py-3">
          <Message title="Set in config.yaml">
            The keys come from <code>auth.turnstile</code> in config.yaml or the environment. Change them there and restart the server.
          </Message>
        </div>
      )}
      {ts.disabled && (
        <div className="px-5 py-3">
          <Message tone="warn" title="Turned off in config.yaml">
            <code>auth.turnstile.disabled</code> is set in config.yaml or the environment, so sign-in protection stays off whatever keys are saved.
          </Message>
        </div>
      )}
      {turningOff && <TurnOffProtectionDialog onClose={() => setTurningOff(false)} onDone={() => { setTurningOff(false); noteTurnedOff(true) }} />}
    </Card>
  )
}

/** Everything that asks for the password again away from home. */
const GUARDED = [
  'Adding a library or changing its folder, and removing a library',
  'Testing, adding, moving or removing a tuner',
  'Adding an admin, resetting a password, deleting a person',
  'Any change to a Radarr, Sonarr or Seerr connection',
  'Signing in to Plex, connecting or disconnecting a Plex server',
  'Turning sign-in protection on or off, or changing its keys',
  'Turning admin settings away from home off',
  'The remote upload limit',
  'Backing up now, or downloading a backup',
]

function RemoteAdmin() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const save = useInstantSave()
  const { away } = useAway()
  const [turningOff, setTurningOff] = useState(false)
  const value = settings.data!.remoteAdmin
  if (!value) return null
  const enabled = value.enabled
  const offAway = !enabled && away
  return (
    <Card>
      <ItemRow
        title="Let admins change server settings from anywhere, over HTTPS"
        detail={<>
          <span className="block">
            {enabled
              ? 'On: admins signed in over HTTPS can change server settings away from home. First-run setup still needs your home network or tailnet.'
              : 'Off: server settings open only on your home network or tailnet.'}
          </span>
          <SaveNote state={save.state} />
        </>}
        control={
          <Switch
            checked={enabled}
            label="Let admins change server settings from anywhere, over HTTPS"
            disabled={value.fromConfig || offAway || save.state.status === 'saving' || update.isPending}
            onChange={(on) => {
              if (!on) return setTurningOff(true)
              void save.run(async () => {
                try {
                  await update.mutateAsync({ remoteAdmin: true })
                } catch (error) {
                  throw visibleConfirmationError(error) ? new Error(remoteAdminRefusal(error)) : error
                }
              }, 'Admins can now change server settings away from home, over HTTPS.')
            }}
          />
        }
      />
      {value.fromConfig && (
        <div className="px-5 py-3">
          <Message title="Set by network.remote_admin in config.yaml">The switch is read-only. Change it in config.yaml and restart the server.</Message>
        </div>
      )}
      {offAway && !value.fromConfig && (
        <div className="px-5 py-3">
          <Message title="Off, and you’re away from home">It can only be turned on from your home network or tailnet. That’s what keeps a stolen sign-in from opening it.</Message>
        </div>
      )}
      {enabled && (
        <div className="px-5 py-4 text-sm">
          <h4 className="font-medium">Away from home, these ask for your password again</h4>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-neutral-300 marker:text-neutral-500">
            {GUARDED.map((g) => <li key={g}>{g}</li>)}
          </ul>
          <p className="mt-3 text-neutral-400">One entry lasts 10 minutes on this browser. Each of these actions taken away from home is written to the server’s log.</p>
        </div>
      )}
      {turningOff && <TurnOffRemoteAdminDialog away={away} onClose={() => setTurningOff(false)} />}
    </Card>
  )
}
