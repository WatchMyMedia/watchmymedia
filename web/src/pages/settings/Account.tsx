import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { revokeSession, sessionsQuery, type Session } from '../../api/account'
import { useAuth } from '../../authContext'
import { Button, Card, HubPage, ItemRow, LoadingRows, Message, Pill, Section } from '../../components/settings/Frame'
import { ConfirmDialog } from '../../components/settings/Dialogs'
import { useGo, useSettingsPage } from '../../components/settings/hooks'
import { relativeTime } from '../../lib/format'
import { settingsCrumbs, settingsHref } from '../../lib/settingsRoutes'
import { accountLede, deviceName, linkHref, sessionLine, signOutError, signOutTitle } from '../../lib/youSettings'
import { ChangePasswordFlow } from './you/ChangePassword'
import { LinkTVFlow } from './you/LinkTV'
import { useFlash, without } from './you/hooks'

/**
 * Settings › You › Account & devices: who you
 * are, your password as a one-step task, and where you are signed in. Link a
 * TV is a flow at /settings/account/link; /link, which a TV's QR code opens,
 * goes to its second step.
 */
export function AccountSettings() {
  const page = useSettingsPage()
  if (page?.task === 'password') return <ChangePasswordFlow />
  if (page?.task === 'link') return <LinkTVFlow params={page.params} />
  return <AccountHub />
}

function AccountHub() {
  const auth = useAuth()
  const qc = useQueryClient()
  const go = useGo()
  const isAdmin = auth.user?.isAdmin ?? false
  const canSeeAll = isAdmin && auth.adminAllowed
  const [all, setAll] = useState(false)
  const everyone = all && canSeeAll
  const sessions = useQuery(sessionsQuery(everyone))
  const [flash] = useFlash('account')
  const [asking, setAsking] = useState<Session | null>(null)
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({})
  const [gone, setGone] = useState(false)
  const revoke = useMutation({
    mutationFn: async (s: Session) => (s.current ? auth.logout() : revokeSession(s.id)),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['sessions'] }),
  })
  const signOut = (s: Session) => {
    setGone(false)
    setRowErrors((r) => without(r, s.id))
    revoke.mutate(s, {
      onSuccess: () => setAsking(null),
      onError: (e) => {
        setAsking(null)
        const result = signOutError(e)
        if (result.gone) {
          setGone(true)
          void qc.invalidateQueries({ queryKey: ['sessions'] })
        } else setRowErrors((r) => ({ ...r, [s.id]: result.text }))
      },
    })
  }
  const items = sessions.data?.items
  const theirs = !!asking && asking.userId !== auth.user?.id

  return (
    <HubPage
      crumbs={settingsCrumbs('account')}
      title="Account & devices"
      lede={accountLede(auth.user?.name ?? '', isAdmin, items?.length, everyone)}
      banner={
        <>
          {flash && <Message tone="ok" title={flash.title}>{flash.body}</Message>}
          {gone && <Message title="That device was already signed out">It signed out, or someone else signed it out, a moment ago. The list is up to date.</Message>}
        </>
      }
    >
      <Section title="Password">
        <Card>
          <ItemRow
            title="Your password"
            detail={flash ? 'Changed just now.' : 'Changing it signs out every device except this browser.'}
            control={<Button small onClick={() => go(settingsHref('account', 'password'))}>Change password</Button>}
          />
        </Card>
      </Section>
      <Section
        title="Signed-in devices"
        aside={
          <div className="flex flex-wrap items-center justify-end gap-2">
            {canSeeAll ? (
              <>
                <label htmlFor="devices-whose" className="sr-only">Whose devices</label>
                <select id="devices-whose" value={all ? 'all' : 'mine'} onChange={(e) => { setAll(e.target.value === 'all'); setGone(false); setRowErrors({}) }}
                  className="min-h-[36px] rounded-md border border-line-2 bg-ground px-2.5 text-sm text-neutral-100">
                  <option value="mine">Yours</option>
                  <option value="all">Everyone’s</option>
                </select>
              </>
            ) : isAdmin && <span className="text-[13px] text-neutral-400">Everyone’s devices are shown on your home network or tailnet.</span>}
            <Button small onClick={() => go(linkHref('code'))}>Link a TV</Button>
          </div>
        }
      >
        {items ? (
          <Card>
            {items.map((s) => (
              <ItemRow
                key={s.id}
                title={<span className="inline-flex items-center gap-2.5"><DeviceIcon session={s} />{deviceName(s)}</span>}
                pill={<><Pill>{s.kind === 'device' ? 'App' : 'Browser'}</Pill>{s.current && <Pill tone="now">This device</Pill>}</>}
                detail={rowErrors[s.id] ? <span role="alert" className="text-red-300">{rowErrors[s.id]}</span> : sessionLine(s, everyone, relativeTime)}
                control={<Button small onClick={() => setAsking(s)}>Sign out…</Button>}
              />
            ))}
            {items.length === 0 && <p className="px-5 py-4 text-sm text-neutral-400">No devices are signed in.</p>}
          </Card>
        ) : sessions.isError ? (
          <Message tone="err" title="Couldn’t load your devices" actions={<Button small onClick={() => void sessions.refetch()}>Try again</Button>}>
            The server didn’t answer. Your devices are as they were.
          </Message>
        ) : <LoadingRows rows={3} />}
        <p className="mt-2.5 text-sm text-neutral-400">Browsers stay signed in for 30 days without use, TV and phone apps for a year.</p>
      </Section>

      {asking && (asking.current ? (
        <ConfirmDialog
          title="Sign out of this browser?"
          lede="You go back to the sign-in page. Your other devices stay signed in."
          confirmLabel="Sign out"
          busy={revoke.isPending}
          onClose={() => setAsking(null)}
          onConfirm={() => signOut(asking)}
        />
      ) : (
        <ConfirmDialog
          title={signOutTitle(asking, auth.user?.id)}
          stops={['It goes back to its sign-in screen at once, even in the middle of a video.']}
          stays={[theirs ? `${asking.userName}’s watch history, settings and other devices.` : 'Your watch history, your settings and your other devices.']}
          confirmLabel="Sign out device"
          busy={revoke.isPending}
          onClose={() => setAsking(null)}
          onConfirm={() => signOut(asking)}
        >
          <p className="mt-3 text-sm text-neutral-400">To use it again, sign in on it, or link it with a code.</p>
        </ConfirmDialog>
      ))}
    </HubPage>
  )
}

/** A TV, a phone, or a computer, guessed from the app or browser name. */
function DeviceIcon({ session: s }: { session: Session }) {
  const label = `${s.product ?? ''} ${s.name ?? ''}`
  const kind = /\btv\b/i.test(label) ? 'tv' : s.kind === 'device' || /android|iphone|ipad|ios/i.test(label) ? 'phone' : 'computer'
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0 text-neutral-400">
      {kind === 'tv' && <><rect x="2" y="5" width="20" height="13" rx="2" /><path d="M8 21h8" /></>}
      {kind === 'phone' && <><rect x="6" y="2" width="12" height="20" rx="2" /><path d="M11 18h2" /></>}
      {kind === 'computer' && <><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M2 20h20" /></>}
    </svg>
  )
}
