import { describe, expect, it } from 'vitest'
import { firstOpenStep, guideProgress, nextStep, phoneStepLine, previousStep, resolveStep, stepStates, type FlowStep } from './flowSteps'

const steps: FlowStep[] = [
  { key: 'people', label: 'People' },
  { key: 'libraries', label: 'Libraries' },
  { key: 'review', label: 'Review', guarded: true },
  { key: 'saved', label: 'Saved' },
]
const doneUpTo = (n: number) => (key: string) => steps.findIndex((s) => s.key === key) < n

describe('resolveStep', () => {
  it('opens the first unfinished step when none is asked for', () => {
    expect(resolveStep(steps, undefined, doneUpTo(0)).key).toBe('people')
    expect(resolveStep(steps, undefined, doneUpTo(2)).key).toBe('review')
  })
  it('opens an earlier step to change it', () => {
    expect(resolveStep(steps, 'people', doneUpTo(2)).key).toBe('people')
  })
  it('sends a step whose earlier steps aren’t done to the first that isn’t', () => {
    expect(resolveStep(steps, 'review', doneUpTo(1)).key).toBe('libraries')
    expect(resolveStep(steps, 'saved', doneUpTo(0)).key).toBe('people')
  })
  it('treats an unknown step as none', () => {
    expect(resolveStep(steps, 'nope', doneUpTo(1)).key).toBe('libraries')
  })
  it('ends on the finished screen when everything is done', () => {
    expect(firstOpenStep(steps, () => true).key).toBe('saved')
    expect(resolveStep(steps, 'saved', () => true).key).toBe('saved')
  })
})

describe('Back and Next', () => {
  it('Back names the step before, and there is none on the first or finished step', () => {
    expect(previousStep(steps, 'libraries')?.key).toBe('people')
    expect(previousStep(steps, 'people')).toBeNull()
    expect(previousStep(steps, 'saved')).toBeNull()
    expect(nextStep(steps, 'review')?.key).toBe('saved')
    expect(nextStep(steps, 'saved')).toBeNull()
  })
})

describe('the stepper', () => {
  it('ticks finished steps, and every step on the finished screen', () => {
    expect(stepStates(steps, 'libraries')).toEqual(['done', 'now', 'todo', 'todo'])
    expect(stepStates(steps, 'saved')).toEqual(['done', 'done', 'done', 'done'])
  })
  it('reads as a line on phones', () => {
    expect(phoneStepLine(steps, 'libraries')).toBe('Step 2 of 4 · Libraries · next: Review')
    expect(phoneStepLine(steps, 'saved')).toBe('Step 4 of 4 · Saved')
  })
})

describe('guideProgress', () => {
  it('counts what is shown and finds the task to do now', () => {
    const p = guideProgress([
      { key: 'tuner', done: true },
      { key: 'access', done: false },
      { key: 'guide', done: false, optional: true, waitsFor: 'access' },
      { key: 'recordings', done: false, optional: true, dismissed: true },
    ])
    expect(p).toEqual({ done: 1, total: 3, now: 'access', complete: false })
  })
  it('is complete when only optional tasks are left', () => {
    expect(guideProgress([{ key: 'a', done: true }, { key: 'b', done: false, optional: true }]).complete).toBe(true)
  })
})
