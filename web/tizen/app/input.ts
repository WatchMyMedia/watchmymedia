import { actionFor, HoldDetector, type KeyInput } from './keys'
import { KeyRouter, type KeyHandler } from './router'
import { FocusEngine, type FocusHooks } from './focus'

/** The event a held OK sends to the focused element; kit parts take it as `onHold`. */
export const HOLD_EVENT = 'tvhold'
/** The event a row gets when a run of ◀ stops at its first card: it gives a little. */
export const ROW_STOP_EVENT = 'tv-row-stop'

function editing(el: Element | null) {
  return el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement
}

/**
 * The app's one keyboard: the router that hands keys to screens, the focus
 * engine that moves focus, and the app's own handler for what nobody took
 * (Return through the stack, the leave dialog). OK counts on keyup, so Hold
 * OK can tell itself apart; a field being typed in keeps its own keys.
 */
export class Input {
  readonly router = new KeyRouter()
  readonly focus: FocusEngine
  private appHandler?: KeyHandler
  private hold: HoldDetector

  constructor(private root: HTMLElement = document.body, hooks: FocusHooks = {}) {
    this.focus = new FocusEngine(root, hooks)
    this.hold = new HoldDetector(() => this.dispatch({ action: 'hold', repeat: false }))
    document.addEventListener('keydown', this.onKeyDown)
    document.addEventListener('keyup', this.onKeyUp)
    window.addEventListener('blur', this.onBlur)
  }

  dispose() {
    document.removeEventListener('keydown', this.onKeyDown)
    document.removeEventListener('keyup', this.onKeyUp)
    window.removeEventListener('blur', this.onBlur)
    this.focus.dispose()
  }

  /** The handler under every screen's: set by the start screens or the shell, whichever is showing. */
  setAppHandler(handler: KeyHandler | undefined) { this.appHandler = handler }

  /** Sends an action as if its key was pressed (on-screen hints, tests). */
  dispatch(input: KeyInput): boolean {
    return this.router.dispatch(input, (i) => this.appHandler?.(i) === true || this.fallback(i))
  }

  private fallback(input: KeyInput): boolean {
    const active = document.activeElement as HTMLElement | null
    switch (input.action) {
      case 'up': case 'down': case 'left': case 'right':
        return this.focus.move(input.action)
      case 'ok':
        if (active && active !== document.body && this.root.contains(active)) active.click()
        return true
      case 'hold':
        active?.dispatchEvent(new CustomEvent(HOLD_EVENT, { bubbles: true }))
        return true
      default:
        return false
    }
  }

  private onKeyDown = (event: KeyboardEvent) => {
    if (editing(document.activeElement)) return
    const input = actionFor(event)
    if (!input) return
    event.preventDefault()
    if (input.action === 'left') this.focus.run.press(event.timeStamp, event.repeat)
    else this.focus.run.reset()
    if (input.action === 'ok') { this.hold.keydown(); return }
    this.dispatch(input)
  }

  private onKeyUp = (event: KeyboardEvent) => {
    const input = actionFor(event)
    if (input?.action === 'left') this.focus.run.release()
    if (input?.action !== 'ok') return
    // An OK that started in a field (Enter to finish typing) ends here too.
    if (!this.hold.pressing) return
    event.preventDefault()
    if (this.hold.keyup()) this.dispatch({ action: 'ok', repeat: false })
  }

  private onBlur = () => this.hold.cancel()
}
