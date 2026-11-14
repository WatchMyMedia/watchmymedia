import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { changePassword, sessionsQuery } from '../../../api/account'
import { useAuth } from '../../../authContext'
import { Card, Field, LoadingRows, Message, fieldInput } from '../../../components/settings/Frame'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { FlowFrame } from '../../../components/settings/Flow'
import { errorText } from '../../../components/settings/hooks'
import { forgetGrant } from '../../../lib/passwordGrant'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { changePasswordLabel, deviceName, passwordChangeError, passwordChangedBanner, passwordFieldErrors, type Notice } from '../../../lib/youSettings'
import { setFlash, useLeaveAfterSave } from './hooks'

/**
 * Change your password (PLAN-ADMIN-SETTINGS.md §5.10): one step that names
 * the devices it will sign out and puts their count on its button. It ends
 * on Account & devices with a banner and the devices list fetched again
 * (loose end 6). The password is typed once.
 */
export function ChangePasswordFlow() {
  const auth = useAuth()
  const qc = useQueryClient()
  const { leaving, leave } = useLeaveAfterSave()
  const sessions = useQuery(sessionsQuery(false))
  const others = (sessions.data?.items ?? []).filter((s) => !s.current && s.userId === auth.user?.id)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [fields, setFields] = useState<{ current?: string; new?: string }>({})
  const [problem, setProblem] = useState<Notice | null>(null)
  const change = useMutation({ mutationFn: () => changePassword(current, next) })
  const submit = () => {
    const mistakes = passwordFieldErrors(current, next)
    setFields(mistakes)
    setProblem(null)
    if (mistakes.current || mistakes.new) return
    const count = sessions.data ? others.length : undefined
    change.mutate(undefined, {
      onSuccess: () => {
        // The server forgets admin password confirmations with the change.
        forgetGrant()
        void qc.invalidateQueries({ queryKey: ['sessions'] })
        setFlash('account', passwordChangedBanner(count))
        leave(settingsHref('account'))
      },
      onError: (e) => {
        const p = passwordChangeError(e, errorText(e))
        if (p.field) setFields({ [p.field]: p.text })
        else setProblem({ title: p.title, body: p.body })
      },
    })
  }
  return (
    <FlowFrame
      tab="account"
      crumbs={settingsCrumbs('account', { label: 'Change password' })}
      title="Change your password"
      lede="This browser stays signed in. Every other device is signed out and has to sign in again with the new password."
      help={
        <>
          <h4>Signing a TV back in</h4>
          <p>On the TV, choose Sign in with a code, then use Link a TV on Account &amp; devices. No typing on the TV.</p>
        </>
      }
      back={{ label: 'Cancel', href: settingsHref('account') }}
      next={{ label: changePasswordLabel(others.length), onClick: submit, busy: change.isPending, busyLabel: 'Changing your password…' }}
    >
      <LeaveCheck when={(current !== '' || next !== '') && !leaving} what="Your password hasn’t been changed." />
      <div className="max-w-md">
        {/* Lets a password manager tell which account the new password is for. */}
        <input type="text" name="username" autoComplete="username" value={auth.user?.name ?? ''} readOnly hidden />
        <Field label="Current password" error={fields.current}>
          {(p) => <input {...p} type="password" value={current} onChange={(e) => { setCurrent(e.target.value); setFields((f) => ({ ...f, current: undefined })) }} autoComplete="current-password" className={fieldInput} />}
        </Field>
        <Field label="New password" hint="8 to 256 characters." error={fields.new}>
          {(p) => <input {...p} type="password" value={next} onChange={(e) => { setNext(e.target.value); setFields((f) => ({ ...f, new: undefined })) }} autoComplete="new-password" maxLength={256} className={fieldInput} />}
        </Field>
      </div>
      <div className="mt-6 max-w-xl">
        {sessions.data ? (
          <Card padded>
            <h3 className="font-semibold">{others.length ? 'Signed out when you change it' : 'No other device is signed in'}</h3>
            {others.length > 0 && (
              <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
                {others.map((s) => <li key={s.id}>{deviceName(s)} · {s.kind === 'device' ? 'App' : 'Browser'}</li>)}
              </ul>
            )}
            {auth.user?.isAdmin && <p className="mt-2.5 text-sm text-neutral-400">Password confirmations for admin settings away from home are forgotten too.</p>}
          </Card>
        ) : sessions.isError ? (
          <Message tone="warn" title="Couldn’t list your devices">Every device except this browser is still signed out when you change your password.</Message>
        ) : <LoadingRows rows={1} />}
      </div>
      {problem && <Message tone="err" title={problem.title} focus className="mt-5 max-w-xl">{problem.body}</Message>}
    </FlowFrame>
  )
}
