import type { KeyboardEvent } from 'react'

// The player's shared button looks, kept apart from its components.

/** The player's round button on the picture: 44 px at phone width, 48 px from md up. */
export const playerButton =
  'inline-grid size-11 shrink-0 place-items-center rounded-full text-neutral-100 hover:bg-white/14 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent md:size-12'

/**
 * At phone width the sheet starts under the picture, which keeps the top of
 * the screen (16:9 of the width, at most 45% of the height), so the
 * subtitles stay in sight while their timing changes.
 */
export const PHONE_PICTURE = 'max-md:h-[min(56.25vw,45dvh)]'
export const PHONE_SHEET_TOP = 'max-md:top-[min(56.25vw,45dvh)]'

export const capClass = 'mt-1.5 text-[11.5px] font-bold uppercase tracking-[.08em] text-dim'

/** Up and Down move along a group of choices, as in a radio group. */
export function moveInGroup(e: KeyboardEvent<HTMLDivElement>) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') return
  const all = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>('[role="radio"]:not(:disabled)'))
  const at = all.indexOf(document.activeElement as HTMLButtonElement)
  const to = e.key === 'Home' ? 0 : e.key === 'End' ? all.length - 1 : e.key === 'ArrowDown' ? at + 1 : at - 1
  e.preventDefault()
  all[(to + all.length) % all.length]?.focus()
}

