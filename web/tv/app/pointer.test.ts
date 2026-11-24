import { describe, expect, it } from 'vitest'
import { gridWindow, rowWindow } from './focusRules'
import { RepeatPace } from './input'

describe('a remote that points', () => {
  it('holds a row still while the pointer focuses cards in view, and moves it for one out of view', () => {
    // Six in view, the row moved along to the third card.
    expect(rowWindow(5, 20, 6, 3, 2).start).toBe(2)
    expect(rowWindow(7, 20, 6, 3, 2).start).toBe(2)
    expect(rowWindow(8, 20, 6, 3, 2).start).toBe(7)
    expect(rowWindow(1, 20, 6, 3, 2).start).toBe(0)
    // Without the pointer, the card before the focused one leads, as ever.
    expect(rowWindow(5, 20, 6).start).toBe(4)
    // A kept place past the row's last full screen isn't kept.
    expect(rowWindow(19, 20, 6, 3, 16).start).toBe(14)
  })

  it('holds a grid still the same way, a row at a time', () => {
    // Six across, two rows in view from the fourth row (cards 18–29).
    expect(gridWindow(25, 120, 6, 2, 2, 2, 3).first).toBe(3)
    expect(gridWindow(31, 120, 6, 2, 2, 2, 3).first).toBe(4)
    expect(gridWindow(25, 120, 6, 2).first).toBe(3)
    expect(gridWindow(29, 120, 6, 2, 2, 2, 3).from).toBe(6)
  })
})

describe('held keys', () => {
  const press = (repeat: boolean) => ({ action: 'right' as const, repeat })

  it('acts on a repeat at most once in the platform’s pace, and on every first press', () => {
    const pace = new RepeatPace(100)
    const taken = [[press(false), 0], [press(true), 25], [press(true), 50], [press(true), 100], [press(true), 125], [press(true), 205], [press(false), 210]]
      .map(([input, at]) => pace.take(input as ReturnType<typeof press>, at as number))
    expect(taken).toEqual([true, false, false, true, false, true, true])
  })

  it('takes every repeat when the TV’s own pace is the one the app was tuned for', () => {
    const pace = new RepeatPace(0)
    expect([0, 1, 2].map((at) => pace.take(press(true), at))).toEqual([true, true, true])
  })
})
