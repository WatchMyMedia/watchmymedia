import { describe, expect, it, vi } from 'vitest'
import { NavStack } from './nav'
import { KeyRouter } from './router'
import { gridWindow, LeftRun, remember, rowWindow, RUN_MS } from './focusRules'
import { isSection, sameRoute, sectionOf, type Route } from './routes'

const stack = () => new NavStack<Route>({ name: 'home' }, { isSection, same: sameRoute })
const names = (s: NavStack<Route>) => s.snapshot().map((e) => e.route.name)

describe('the screens stack', () => {
  it('puts sections on Home and pages on what opened them', () => {
    const s = stack()
    s.openSection({ name: 'library', type: 'movie' })
    s.push({ name: 'movie', id: 3 })
    expect(names(s)).toEqual(['home', 'library', 'movie'])
    expect(sectionOf(s.section())).toBe('movies')
    s.openSection({ name: 'live' })
    expect(names(s)).toEqual(['home', 'live'])
    expect(s.back()).toBe(true)
    expect(names(s)).toEqual(['home'])
    expect(s.back()).toBe(false)
  })

  it('keeps Home and its memory when switching sections, and goes back to a section still open', () => {
    const s = stack()
    const home = s.top
    remember(home.memory, 'card-7', ['row-continue'])
    s.openSection({ name: 'library', type: 'tv' })
    s.push({ name: 'show', id: 9 })
    s.openSection({ name: 'library', type: 'movie' })
    expect(s.snapshot()[0]).toBe(home)
    expect(home.memory).toEqual({ last: 'card-7', groups: { 'row-continue': 'card-7' } })
    s.push({ name: 'list', id: 2 })
    expect(s.openSection({ name: 'library', type: 'movie' })).toBe(true)
    expect(names(s)).toEqual(['home', 'library'])
    expect(s.openSection({ name: 'library', type: 'movie' })).toBe(false)
    s.openSection({ name: 'home' })
    expect(s.snapshot()).toEqual([home])
  })

  it('tells TV Shows from Movies', () => {
    expect(sectionOf({ name: 'library', type: 'tv' })).toBe('shows')
    expect(sameRoute({ name: 'library', type: 'tv' }, { name: 'library', type: 'movie' })).toBe(false)
    expect(sameRoute({ name: 'movie', id: 1 }, { name: 'movie', id: 1 })).toBe(true)
  })
})

describe('Return order', () => {
  it('closes a modal, then an overlay, then the screen, before the stack', () => {
    const r = new KeyRouter()
    const calls: string[] = []
    const back = { action: 'back' as const, repeat: false }
    const fallback = () => { calls.push('stack'); return true }
    r.add('screen', (i) => { if (i.action === 'back') { calls.push('screen'); return calls.filter((c) => c === 'screen').length === 1 } })
    const closeOverlay = r.add('overlay', (i) => { if (i.action === 'back') { calls.push('overlay'); return true } })
    const closeModal = r.add('modal', (i) => { if (i.action === 'back') { calls.push('modal'); return true } })
    r.dispatch(back, fallback); closeModal()
    r.dispatch(back, fallback); closeOverlay()
    r.dispatch(back, fallback)
    r.dispatch(back, fallback)
    expect(calls).toEqual(['modal', 'overlay', 'screen', 'screen', 'stack'])
  })

  it('gives a modal every key: others stop there, arrows reach the trapped focus', () => {
    const r = new KeyRouter()
    const screen = vi.fn()
    const fallback = vi.fn(() => true)
    r.add('screen', screen)
    r.add('modal', () => false)
    r.dispatch({ action: 'playPause', repeat: false }, fallback)
    r.dispatch({ action: 'back', repeat: false }, fallback)
    expect(fallback).not.toHaveBeenCalled()
    r.dispatch({ action: 'down', repeat: false }, fallback)
    expect(fallback).toHaveBeenCalledTimes(1)
    expect(screen).not.toHaveBeenCalled()
  })

  it('asks the newest handler of a layer first', () => {
    const r = new KeyRouter()
    const calls: number[] = []
    r.add('screen', () => { calls.push(1); return true })
    r.add('screen', () => { calls.push(2); return true })
    r.dispatch({ action: 'ok', repeat: false })
    expect(calls).toEqual([2])
  })
})

describe('rows and grids', () => {
  it('keeps the card before the focused one in view and draws only the cards near it', () => {
    expect(rowWindow(0, 20, 5)).toEqual({ start: 0, from: 0, to: 8 })
    expect(rowWindow(1, 20, 5).start).toBe(0)
    expect(rowWindow(6, 20, 5)).toEqual({ start: 5, from: 2, to: 13 })
    expect(rowWindow(19, 20, 5).start).toBe(15)
    expect(rowWindow(2, 3, 5)).toEqual({ start: 0, from: 0, to: 3 })
  })

  it('stops a quick run of ◀ but not a single press', () => {
    const run = new LeftRun()
    run.press(1000, false)
    expect(run.inRun).toBe(false)
    run.release()
    run.press(1000 + RUN_MS - 1, false)
    expect(run.inRun).toBe(true)
    run.release()
    run.press(3000, false)
    expect(run.inRun).toBe(false)
    run.press(3010, true)
    expect(run.inRun).toBe(true)
    run.reset()
    run.press(3100, false)
    expect(run.inRun).toBe(false)
  })

  it('draws a grid around the focused row and asks for more near the end', () => {
    expect(gridWindow(0, 40, 8, 2)).toEqual({ first: 0, from: 0, to: 32, loadMore: false })
    expect(gridWindow(17, 40, 8, 2)).toEqual({ first: 1, from: 0, to: 40, loadMore: true })
    expect(gridWindow(80, 200, 8, 2)).toEqual({ first: 9, from: 56, to: 104, loadMore: false })
  })
})
