// The rules every stepped flow follows (PLAN-ADMIN-SETTINGS.md §3.2):
// which step an address opens, what Back goes to, and how the stepper draws.

export type FlowStep = {
  /** The step's last path segment. */
  key: string
  label: string
  /** Away from home, this step's action asks for the password. */
  guarded?: boolean
  /** Can be skipped, and stays to do on the hub. */
  optional?: boolean
}

/**
 * The step to show for an address. A step whose earlier steps aren't done
 * opens the first one that isn't; an unknown step, or none, opens the first
 * unfinished step. `done(key)` says whether a step's work is saved.
 */
export function resolveStep(steps: readonly FlowStep[], requested: string | undefined, done: (key: string) => boolean): FlowStep {
  const first = firstOpenStep(steps, done)
  if (!requested) return first
  const at = steps.findIndex((s) => s.key === requested)
  if (at < 0) return first
  const firstAt = steps.indexOf(first)
  return at <= firstAt ? steps[at] : first
}

/** The first step that isn't done, else the last (the finished screen). */
export function firstOpenStep(steps: readonly FlowStep[], done: (key: string) => boolean): FlowStep {
  return steps.find((s, i) => i === steps.length - 1 || !done(s.key)) ?? steps[steps.length - 1]
}

/** The step Back goes to, or null on the first step and on the finished one. */
export function previousStep(steps: readonly FlowStep[], current: string): FlowStep | null {
  const at = steps.findIndex((s) => s.key === current)
  if (at <= 0 || at === steps.length - 1) return null
  return steps[at - 1]
}

export function nextStep(steps: readonly FlowStep[], current: string): FlowStep | null {
  const at = steps.findIndex((s) => s.key === current)
  return at >= 0 && at < steps.length - 1 ? steps[at + 1] : null
}

export type StepState = 'done' | 'now' | 'todo'

/** How the stepper draws each step: finished steps are ticked, and on the
 * finished screen every step is. */
export function stepStates(steps: readonly FlowStep[], current: string): StepState[] {
  const at = steps.findIndex((s) => s.key === current)
  const finished = at === steps.length - 1
  return steps.map((_, i) => (i < at || (finished && i === at) ? 'done' : i === at ? 'now' : 'todo'))
}

/** "Step 2 of 4 · Libraries · next: Review", the stepper on phones. */
export function phoneStepLine(steps: readonly FlowStep[], current: string) {
  const at = steps.findIndex((s) => s.key === current)
  if (at < 0) return ''
  const next = steps[at + 1]
  return `Step ${at + 1} of ${steps.length} · ${steps[at].label}${next ? ` · next: ${next.label}` : ''}`
}

/** Setup guide on a hub: the count and each task's state, in order. A task is
 * Now when it's the first unfinished one that isn't waiting on another. */
export type GuideTask = { key: string; done: boolean; optional?: boolean; dismissed?: boolean; waitsFor?: string }

export function guideProgress(tasks: readonly GuideTask[]) {
  const shown = tasks.filter((t) => !t.dismissed)
  const done = shown.filter((t) => t.done).length
  const now = shown.find((t) => !t.done && !t.waitsFor)?.key ?? null
  return { done, total: shown.length, now, complete: shown.every((t) => t.done || t.optional) }
}
