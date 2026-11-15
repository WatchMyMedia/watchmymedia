import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useAuth } from '../../authContext'
import { adminAwayWording } from '../../lib/adminAway'
import { phoneStepLine, stepStates, type FlowStep } from '../../lib/flowSteps'
import { parentOf, tabInfo, type Crumb, type SettingsTab } from '../../lib/settingsRoutes'
import { AwayChips, Breadcrumb, Button, GuardedCaption, HrefLink, Help, Outcome } from './Frame'
import { useGo, usePrefersReducedMotion } from './hooks'

export type FooterAction = {
  label: ReactNode
  /** A link to another step or page… */
  href?: string
  /** …or something to run. The primary action runs on Enter too. */
  onClick?: () => void
  guarded?: boolean
  busy?: boolean
  busyLabel?: ReactNode
  disabled?: boolean
  /** Why it is off, said beside it. */
  reason?: ReactNode
  variant?: 'primary' | 'secondary' | 'danger'
}

/**
 * A focused task (PLAN-ADMIN-SETTINGS.md §3.2): the bar with Exit, the
 * breadcrumb and the away chips; the stepper (a progress line on phones);
 * the step's title and why; the fields with help beside them; and the footer
 * with Back, Skip for now and one filled button that names its action.
 *
 * A one-step task leaves out `steps` and gets Cancel and Save in the footer.
 * The page's Settings sidebar and app bar are not drawn while it is open.
 */
export function FlowFrame({
  tab, crumbs, steps, current, stepHref, title, lede, finished, help, back, skip, next, extra, children, wide, keptOut,
}: {
  tab: SettingsTab
  crumbs: Crumb[]
  steps?: readonly FlowStep[]
  current?: string
  /** Finished steps are links back to themselves. */
  stepHref?: (key: string) => string
  title: ReactNode
  lede?: ReactNode
  /** The finished screen: a tick, or ! when it ended part-way. */
  finished?: 'ok' | 'partial'
  help?: ReactNode
  back?: FooterAction
  skip?: FooterAction
  next?: FooterAction
  /** More buttons in the footer, before the primary one. */
  extra?: ReactNode
  children?: ReactNode
  wide?: boolean
  /** What was kept when the gate closes in the middle (§3.4). */
  keptOut?: { kept: ReactNode[]; carryOn: ReactNode[] }
}) {
  const auth = useAuth()
  const go = useGo()
  const exit = parentOf(crumbs)
  const server = tabInfo(tab).group === 'server'
  const isAdmin = auth.user?.isAdmin ?? false
  const closed = server && isAdmin && !auth.adminAllowed
  const member = server && !isAdmin
  const formId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const reduced = usePrefersReducedMotion()
  const index = steps && current ? steps.findIndex((s) => s.key === current) : -1
  // Steps slide in the direction of travel; focus moves to the new step's heading (§6).
  const [travel, setTravel] = useState({ index, direction: '' })
  if (travel.index !== index) setTravel({ index, direction: index > travel.index ? 'settings-step-forward' : 'settings-step-back' })
  const direction = travel.direction
  const firstRender = useRef(true)
  useEffect(() => {
    if (firstRender.current) { firstRender.current = false; return }
    heading.current?.focus()
  }, [index])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!next || next.disabled || next.busy) return
    if (next.href) go(next.href)
    else next.onClick?.()
  }

  let body: ReactNode
  if (member) {
    body = <p className="rounded-xl border border-line bg-surface p-5">Only admins can change the server’s settings.</p>
  } else if (closed) {
    const words = adminAwayWording(auth.adminLimit ?? 'network')
    body = (
      <div className="space-y-5">
        <FinishedHead tone="partial" title="Server settings just closed for this browser" lede={words.long} />
        <Outcome
          changed={keptOut?.kept ?? ['Steps that had already saved stay saved.', 'Nothing on this step was saved.']}
          next={keptOut?.carryOn ?? [<>Carry on from your home network or tailnet. The {tabInfo(tab).label} page will offer to continue.</>]}
          titles={['What was kept', 'What to do']}
        />
      </div>
    )
  } else {
    body = (
      <div key={current} className={reduced ? '' : direction}>
        {finished ? (
          <FinishedHead tone={finished} title={title} lede={lede} headingRef={heading} />
        ) : (
          <>
            <h2 ref={heading} tabIndex={-1} className="text-xl font-semibold tracking-tight outline-none sm:text-[22px]">{title}</h2>
            {lede && <p className="mt-1.5 max-w-2xl text-neutral-400">{lede}</p>}
          </>
        )}
        {help ? (
          <div className="mt-6 grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_17rem]">
            <div className="min-w-0">{children}</div>
            <Help>{help}</Help>
          </div>
        ) : (
          <div className="mt-6">{children}</div>
        )}
      </div>
    )
  }

  const footerNext: FooterAction | undefined = member || closed ? { label: 'Back to Settings', href: '/settings' } : next
  return (
    <div className="flex min-h-screen flex-col bg-ground">
      <header className="sticky top-0 z-30 border-b border-line bg-surface/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-1.5 px-4 py-2.5 md:px-7">
          <HrefLink href={exit.href ?? '/settings'} className="inline-flex min-h-[40px] items-center gap-1.5 rounded-md pr-2 text-sm text-neutral-300 hover:text-neutral-100">
            <span aria-hidden="true">✕</span>
            <span className="sm:hidden">Exit</span>
            <span className="hidden sm:inline">Exit to {exit.label}</span>
          </HrefLink>
          <Breadcrumb crumbs={crumbs} className="order-3 basis-full sm:order-none sm:basis-auto sm:flex-1" />
          <span className="ml-auto sm:ml-0"><AwayChips /></span>
        </div>
      </header>
      {steps && steps.length > 1 && current && !member && <Stepper steps={steps} current={current} stepHref={finished || closed ? undefined : stepHref} />}
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 pt-6 pb-10 md:px-7">
        <form id={formId} onSubmit={submit} noValidate className={wide ? '' : 'max-w-4xl'}>
          {body}
        </form>
      </main>
      {(back || skip || footerNext || extra) && (
        <footer className="sticky bottom-0 z-20 border-t border-line bg-surface/95 backdrop-blur">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-3 md:px-7">
            {back && !member && !closed && <FooterButton action={{ variant: 'secondary', ...back, label: <><span aria-hidden="true">←</span> {back.label}</> }} className="flex-1 sm:flex-none" />}
            <span className="hidden flex-1 sm:block" />
            {footerNext?.guarded && !member && !closed && <GuardedCaption className="order-first basis-full sm:order-none sm:max-w-[16rem] sm:basis-auto sm:text-right" />}
            {footerNext?.reason && footerNext.disabled && <span className="order-first basis-full text-[13px] text-warn sm:order-none sm:max-w-[18rem] sm:basis-auto sm:text-right">{footerNext.reason}</span>}
            {!member && !closed && extra}
            {skip && !member && !closed && <FooterButton action={{ variant: 'secondary', ...skip }} className="flex-1 sm:flex-none" />}
            {footerNext && <FooterButton action={{ variant: 'primary', ...footerNext }} submitFor={footerNext.href ? undefined : formId} className="basis-full sm:basis-auto" />}
          </div>
        </footer>
      )}
    </div>
  )
}

function FooterButton({ action, submitFor, className = '' }: { action: FooterAction; submitFor?: string; className?: string }) {
  const go = useGo()
  return (
    <Button
      variant={action.variant}
      guarded={action.guarded}
      busy={action.busy}
      busyLabel={action.busyLabel}
      disabled={action.disabled}
      type={submitFor ? 'submit' : 'button'}
      form={submitFor}
      onClick={submitFor ? undefined : () => (action.href ? go(action.href) : action.onClick?.())}
      className={className}
    >
      {action.label}
    </Button>
  )
}

function FinishedHead({ tone, title, lede, headingRef }: { tone: 'ok' | 'partial'; title: ReactNode; lede?: ReactNode; headingRef?: React.Ref<HTMLHeadingElement> }) {
  return (
    <div className="flex items-start gap-3.5">
      <span aria-hidden="true" className={`grid h-10 w-10 shrink-0 place-items-center rounded-full border-[1.5px] text-xl font-bold ${tone === 'ok' ? 'border-emerald-800 bg-emerald-950/60 text-emerald-300' : 'border-orange-900 bg-orange-950/50 text-warn'}`}>
        {tone === 'ok' ? '✓' : '!'}
      </span>
      <div className="min-w-0">
        <h2 ref={headingRef} tabIndex={-1} className="text-xl font-semibold tracking-tight outline-none sm:text-[22px]">{title}</h2>
        {lede && <p className="mt-1.5 max-w-2xl text-neutral-400">{lede}</p>}
      </div>
    </div>
  )
}

/** Numbered steps; finished ones are ticked links back. On phones, a
 * progress bar and "Step 2 of 4 · Libraries · next: Review". */
export function Stepper({ steps, current, stepHref }: { steps: readonly FlowStep[]; current: string; stepHref?: (key: string) => string }) {
  const states = stepStates(steps, current)
  return (
    <div className="mx-auto w-full max-w-6xl px-4 pt-4 md:px-7">
      <div className="sm:hidden">
        <div className="flex gap-1" aria-hidden="true">
          {states.map((s, i) => <i key={i} className={`h-1 flex-1 rounded-full ${s === 'done' ? 'bg-emerald-400' : s === 'now' ? 'bg-accent' : 'bg-line-2'}`} />)}
        </div>
        <p className="mt-2 text-[13px] text-neutral-400">{phoneStepLine(steps, current)}</p>
      </div>
      <ol aria-label="Steps" className="hidden sm:flex">
        {steps.map((step, i) => {
          const state = states[i]
          const mark = state === 'done' ? '✓' : String(i + 1)
          const label = state === 'done' && stepHref && i < steps.length - 1 ? (
            <HrefLink href={stepHref(step.key)} className="underline decoration-line-2 underline-offset-[3px] hover:text-neutral-100">{step.label}</HrefLink>
          ) : step.label
          return (
            <li key={step.key} aria-current={state === 'now' ? 'step' : undefined} className={`relative flex flex-1 flex-col items-center gap-1.5 px-1 text-center text-[13px] ${state === 'now' ? 'font-semibold text-neutral-100' : state === 'done' ? 'text-neutral-300' : 'text-neutral-500'}`}>
              {i > 0 && <span aria-hidden="true" className={`absolute top-3.5 right-1/2 h-[1.5px] w-full ${states[i - 1] === 'done' ? 'bg-emerald-800' : 'bg-line-2'}`} />}
              <span aria-hidden="true" className={`relative z-10 grid h-7 w-7 place-items-center rounded-full border-[1.5px] text-[13px] font-semibold ${state === 'done' ? 'border-emerald-800 bg-emerald-950 text-emerald-300' : state === 'now' ? 'border-accent bg-accent text-black' : 'border-line-2 bg-ground'}`}>{mark}</span>
              <span>
                <span className="sr-only">{state === 'done' ? 'Done: ' : state === 'now' ? 'Current: ' : ''}</span>
                {label}
                {step.guarded && <GuardedStepNote />}
              </span>
            </li>
          )
        })}
      </ol>
    </div>
  )
}

function GuardedStepNote() {
  return <AwayOnly><small className="block text-[11.5px] font-normal text-amber-300">asks for your password</small></AwayOnly>
}

function AwayOnly({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const away = (auth.user?.isAdmin ?? false) && auth.adminAllowed && auth.onAdminNetwork === false
  return away ? children : null
}
