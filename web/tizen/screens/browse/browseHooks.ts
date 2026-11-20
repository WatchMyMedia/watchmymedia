import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useState, type CSSProperties, type RefObject } from 'react'
import { scopeName } from '../../app/focus'
import { useEntry, useFocus } from '../../app/hooks'
import type { ScreenRow } from '../../../src/lib/screenRows'
import { placeholderTone } from '../../ui/tones'
import { rowCards, type CardAction, type RowCard, type RowContext } from './rowCards'

// The browsing screens' hooks, apart from their components.

export type CardActionsApi = { run(action: CardAction | undefined): void; say(text: string): void }

export const CardActionsContext = createContext<CardActionsApi>({ run: () => {}, say: () => {} })

/** Carries out a card's OK or Hold OK, and says what happened (`say`), under CardActions. */
export function useCardActions(): CardActionsApi {
  return useContext(CardActionsContext)
}

/** The placeholder gradient a picture loads over, from the boards' tones. */
export function toneStyle(seed: string): CSSProperties {
  const [a, b] = placeholderTone(seed)
  return { '--ph-a': a, '--ph-b': b } as CSSProperties
}

/** The clock a Live TV row reads what is on from, every half minute; still when not `ticking`. */
export function useNow(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!ticking) return
    const timer = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(timer)
  }, [ticking])
  return now
}

/** A row's cards, worked out again only when the row, its context or (Live TV) the clock changes. */
export function useRowCards(row: ScreenRow, base: Omit<RowContext, 'now'>): { cards: RowCard[]; now: number } {
  const now = useNow(row.style === 'live')
  const cards = useMemo(() => rowCards(row, { ...base, now }), [row, base, now])
  return { cards, now }
}

export type FocusHintWords = { ok?: string; hold?: string }

/** What OK and Hold OK do on the element that has focus under `root`, from its `data-hint` and `data-hold`. */
export function useFocusHint(root: RefObject<HTMLElement | null>): FocusHintWords {
  const [words, setWords] = useState<FocusHintWords>({})
  useEffect(() => {
    const el = root.current
    if (!el) return
    const onIn = (e: Event) => {
      const at = (e.target as HTMLElement).closest?.<HTMLElement>('[data-hint]')
      const next = { ok: at?.dataset.hint, hold: at?.dataset.hold }
      setWords((w) => (w.ok === next.ok && w.hold === next.hold ? w : next))
    }
    const onOut = (e: Event) => { if (!el.contains((e as FocusEvent).relatedTarget as Node | null)) setWords({}) }
    // Focus may have come before this listened (the shell puts it back as the screen draws).
    if (document.activeElement && el.contains(document.activeElement)) onIn({ target: document.activeElement } as unknown as Event)
    el.addEventListener('focusin', onIn)
    el.addEventListener('focusout', onOut)
    return () => { el.removeEventListener('focusin', onIn); el.removeEventListener('focusout', onOut) }
  }, [root])
  return words
}

/**
 * Puts focus on a screen that drew its content after a slow load: the shell
 * waits a few seconds for a screen to draw, and a slower server leaves
 * nothing focused. Then focus goes where the screen had it, else to its
 * first item; never away from something that already has focus.
 */
export function useFocusOnArrival() {
  const focus = useFocus()
  const entry = useEntry()
  useLayoutEffect(() => {
    if (!scopeName(document.activeElement)) focus.restore([entry?.memory.last])
    // Once, as the content first draws.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}
