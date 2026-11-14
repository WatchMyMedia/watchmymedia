import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../../../api/client'
import { visibleConfirmationError, withConfirm } from '../../../api/withConfirm'
import { FlowFrame } from '../../../components/settings/Flow'
import { LeaveCheck } from '../../../components/settings/Dialogs'
import { Button, Choice, Field, HrefLink, Message, SectionLabel, fieldInput } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { addRefusal, addSteps, nameProblem, passwordProblem, possessive, resolveAddStep, type FieldError, type StepError } from '../../../lib/usersSettings'
import { usePeople } from './data'
import { clearAddDraft, noteDeleted, setAddDraft, useAddDraft } from './store'

const crumbs = settingsCrumbs('users', { label: 'Add a person' })
const stepHref = (key: string) => settingsHref('users', 'add', key)

/**
 * Add a person (§5.5): Name and role → Password → Added. Name and role are
 * only collected; Password adds them. A role can't be changed later, so it
 * is a choice with what it means, and Admin makes Password ask for the
 * password away from home. There are no invites, so Added says what to tell them.
 */
export function AddPersonFlow({ step: requested }: { step?: string }) {
  const go = useGo()
  const draft = useAddDraft()
  const step = resolveAddStep(requested, draft)
  useEffect(() => { noteDeleted(null) }, [])
  useEffect(() => {
    if (step.key !== requested) go(stepHref(step.key), { replace: true })
  }, [go, requested, step.key])
  if (step.key === 'name') return <NameStep />
  if (step.key === 'password') return <PasswordStep />
  return <AddedStep />
}

function NameStep() {
  const go = useGo()
  const draft = useAddDraft()
  const people = usePeople()
  const names = people.users.data?.items.map((u) => u.name) ?? []
  const [problem, setProblem] = useState<StepError | null>(null)
  const steps = addSteps(draft.admin)
  const name = draft.name.trim() || 'them'
  return (
    <FlowFrame
      tab="users"
      crumbs={crumbs}
      steps={steps}
      current="name"
      stepHref={stepHref}
      title="Who are you adding?"
      next={{
        label: 'Continue to password',
        onClick: () => {
          const p = nameProblem(draft.name, names)
          setProblem(p)
          if (!p) go(stepHref('password'))
        },
      }}
    >
      <Field label="Name" hint="What they type to sign in." error={problem ? <><b className="font-semibold">{problem.title}</b>. {problem.text}</> : draft.nameError}>
        {(props) => (
          <input
            {...props}
            value={draft.name}
            onChange={(e) => { setAddDraft({ name: e.target.value, nameError: undefined }); setProblem(null) }}
            maxLength={64}
            autoComplete="off"
            autoCapitalize="words"
            spellCheck={false}
            autoFocus
            className={`${fieldInput} max-w-md`}
          />
        )}
      </Field>
      <fieldset className="mt-6 max-w-2xl">
        <legend className="mb-2"><SectionLabel>Role · can’t be changed later</SectionLabel></legend>
        <Choice name="role" value="member" checked={!draft.admin} onChange={(on) => on && setAddDraft({ admin: false })} title="Member">
          Watches, keeps their own history and settings, and can request if you allow it.
        </Choice>
        <Choice name="role" value="admin" checked={draft.admin} onChange={(on) => on && setAddDraft({ admin: true })} title="Admin">
          Everything a member can do, plus all of Settings › Server, and everyone’s history, devices and addresses under Activity.
        </Choice>
      </fieldset>
      <p className="sr-only" aria-live="polite">{draft.admin ? `${name} will be an admin.` : `${name} will be a member.`}</p>
    </FlowFrame>
  )
}

function PasswordStep() {
  const qc = useQueryClient()
  const draft = useAddDraft()
  const people = usePeople()
  const names = people.users.data?.items.map((u) => u.name) ?? []
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [recordHistory, setRecordHistory] = useState(true)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [refusal, setRefusal] = useState<StepError | null>(null)
  const name = draft.name.trim()
  const role = draft.admin ? 'an admin' : 'a member'
  const create = useMutation({
    mutationFn: async () => {
      const body = { name, password, isAdmin: draft.admin }
      const add = async () => unwrap(await api.POST('/api/users', { body }))
      const user = draft.admin ? await withConfirm(add, 'add an admin') : await add()
      // There is no history choice on create; it is the person's own switch.
      let historyFailed = false
      if (!recordHistory) {
        try {
          await unwrap(await api.PATCH('/api/users/{id}', { params: { path: { id: user.id } }, body: { recordHistory: false } }))
        } catch {
          historyFailed = true
        }
      }
      return { user, historyFailed }
    },
    onSuccess: ({ user, historyFailed }) => {
      void qc.invalidateQueries({ queryKey: ['users'] })
      void qc.invalidateQueries({ queryKey: ['activity', 'users'] })
      setPassword('')
      // Added is shown by the address effect once this has drawn, so the leave check is already off.
      setAddDraft({ created: { id: user.id, name: user.name, admin: user.isAdmin, recordHistory: recordHistory && !historyFailed, historyFailed } })
    },
    onError: (error) => {
      if (!visibleConfirmationError(error)) return
      const r = addRefusal(error, name, names)
      if (r.field?.field === 'name') {
        // The name was taken in the meantime: Name and role opens again, where it can change.
        setPassword('')
        setAddDraft({ nameError: r.field.text })
        return
      }
      setFieldError(r.field?.text ?? null)
      setRefusal(r.step ?? null)
    },
  })
  const fieldProblem: FieldError | null = fieldError ? { field: 'password', text: fieldError } : null
  return (
    <FlowFrame
      tab="users"
      crumbs={crumbs}
      steps={addSteps(draft.admin)}
      current="password"
      stepHref={stepHref}
      title={`A password for ${name}`}
      lede="You set it and tell them. They can change it later under Account & devices."
      back={{ label: 'Name and role', href: stepHref('name') }}
      next={{
        label: `Add ${name} as ${role}`,
        guarded: draft.admin,
        busy: create.isPending,
        busyLabel: 'Adding…',
        onClick: () => {
          const short = passwordProblem(password)
          setFieldError(short)
          setRefusal(null)
          if (!short) create.mutate()
        },
      }}
    >
      <LeaveCheck when={password !== '' && !create.isPending} what="The password you typed isn’t saved, and no one has been added yet." />
      <Field label="Password" hint="8 characters or more." error={fieldProblem?.text}>
        {(props) => (
          <div className="flex max-w-md gap-2">
            <input
              {...props}
              type={show ? 'text' : 'password'}
              value={password}
              onChange={(e) => { setPassword(e.target.value); setFieldError(null) }}
              maxLength={256}
              autoComplete="new-password"
              autoFocus
              disabled={create.isPending}
              className={fieldInput}
            />
            <Button small onClick={() => setShow(!show)} aria-pressed={show} className="shrink-0 self-center">{show ? 'Hide' : 'Show'}</Button>
          </div>
        )}
      </Field>
      <div className="mt-6 max-w-2xl">
        <Choice name="record-history" type="checkbox" checked={recordHistory} onChange={setRecordHistory} title={`Record ${possessive(name)} watch history`} disabled={create.isPending}>
          Off: plays aren’t kept and Plex history is hidden for them.
        </Choice>
      </div>
      {refusal && <Message tone="err" title={refusal.title} className="mt-6" focus>{refusal.text}</Message>}
    </FlowFrame>
  )
}

function AddedStep() {
  const draft = useAddDraft()
  const people = usePeople()
  const [copied, setCopied] = useState(false)
  const created = draft.created
  if (!created) return null
  const address = window.location.origin
  const canCopy = typeof navigator !== 'undefined' && !!navigator.clipboard
  const plexConnected = people.plex?.status === 'connected'
  const next = [
    people.hasSeerr && <>{created.name} can’t request yet. <HrefLink href={settingsHref('users', created.id)} className="text-neutral-100 underline">Link a Seerr user</HrefLink>.</>,
    plexConnected && <>To bring {possessive(created.name)} Plex history across, <HrefLink href={settingsHref('plex', 'link')} className="text-neutral-100 underline">link them in Plex</HrefLink>.</>,
    created.historyFailed && <>{possessive(created.name)} history is still recorded: turning it off didn’t save. <HrefLink href={settingsHref('users', created.id)} className="text-neutral-100 underline">Turn it off on their page</HrefLink>.</>,
    <>Their history, Seerr user and password are on <HrefLink href={settingsHref('users', created.id)} className="text-neutral-100 underline">{possessive(created.name)} page</HrefLink>.</>,
  ].filter(Boolean)
  return (
    <FlowFrame
      tab="users"
      crumbs={crumbs}
      steps={addSteps(created.admin)}
      current="added"
      finished="ok"
      title={`${created.name} is added`}
      lede={`${created.admin ? 'Admin' : 'Member'} · ${created.recordHistory ? 'history recorded' : 'history not recorded'}`}
      // Clearing the draft opens Name and role again; Users clears it on the way in.
      skip={{ label: 'Add another person', onClick: clearAddDraft }}
      next={{ label: 'Back to Users', href: settingsHref('users') }}
    >
      <div className="grid gap-4 md:grid-cols-2">
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="font-semibold">What to tell {created.name}</h3>
          <ul className="mt-2 list-disc space-y-1.5 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
            <li>The address: <code className="break-all text-neutral-100">{address}</code></li>
            <li>Their name, {created.name}, and the password you chose.</li>
            <li>On a TV, open the app and enter the code at <code className="text-neutral-100">/link</code>.</li>
          </ul>
          {canCopy && (
            <div className="mt-3">
              <Button small onClick={() => void navigator.clipboard.writeText(address).then(() => setCopied(true), () => setCopied(false))}>{copied ? '✓ Copied' : 'Copy address'}</Button>
            </div>
          )}
        </div>
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="font-semibold">What happens next</h3>
          <ul className="mt-2 list-disc space-y-1.5 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
            {next.map((item, i) => <li key={i}>{item}</li>)}
          </ul>
        </div>
      </div>
    </FlowFrame>
  )
}
