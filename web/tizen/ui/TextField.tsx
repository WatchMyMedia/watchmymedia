import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { flushSync } from 'react-dom'
import { useFocus, useInput } from '../app/hooks'
import { IME_CANCEL, IME_DONE } from '../app/keys'

export type TextFieldProps = {
  /** The data-focus id of the field while it isn't being typed in. */
  id: string
  label: string
  value: string
  onChange(value: string): void
  placeholder?: string
  type?: 'text' | 'url' | 'password'
  /** Done (or Enter) on the TV's keyboard. */
  onSubmit?(): void
  autoFocus?: boolean
  autoComplete?: string
}

/**
 * A field typed with the TV's own keyboard. Moving past it never opens the
 * keyboard: OK does, by focusing the real input. Done or Enter finishes
 * (and submits), Cancel or Return just finishes, ▲ ▼ finish and move on.
 * The field's keys stop at the field, so the app never sees them twice.
 */
export function TextField({ id, label, value, onChange, placeholder, type = 'text', onSubmit, autoFocus, autoComplete = 'off' }: TextFieldProps) {
  const [editing, setEditing] = useState(false)
  const ending = useRef(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const focus = useFocus()
  const input = useInput()

  useEffect(() => { if (editing) inputRef.current?.focus() }, [editing])

  /** Back to the field, drawn at once, so a key that comes next finds it. */
  const end = (then?: () => void) => {
    ending.current = true
    flushSync(() => setEditing(false))
    ending.current = false
    focus.focusId(id)
    then?.()
  }
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    const code = e.keyCode
    const done = e.key === 'Enter' || code === IME_DONE
    const cancel = e.key === 'Escape' || code === 10009 || code === IME_CANCEL
    const move = e.key === 'ArrowUp' ? 'up' : e.key === 'ArrowDown' ? 'down' : undefined
    if (!done && !cancel && !move) return
    e.preventDefault()
    e.stopPropagation()
    if (done) end(onSubmit)
    else if (cancel) end()
    else if (move) end(() => input.dispatch({ action: move, repeat: false }))
  }
  const onBlur = () => {
    if (ending.current) return
    // The keyboard closed itself, or a click went elsewhere: stop typing, and keep focus on the field if nothing has it.
    setEditing(false)
    requestAnimationFrame(() => { if (document.activeElement === document.body) focus.focusId(id) })
  }
  const shown = type === 'password' ? '•'.repeat(value.length) : value
  if (editing) {
    return <div className="tv-field tv-field--editing">
      <input ref={inputRef} type={type} aria-label={label} value={value} placeholder={placeholder} autoComplete={autoComplete}
        autoCapitalize="none" spellCheck={false} onChange={(e) => onChange(e.target.value)} onKeyDown={onKeyDown} onBlur={onBlur}/>
    </div>
  }
  return <button type="button" data-focus={id} data-autofocus={autoFocus || undefined} className="tv-field" aria-label={`${label}${value && type !== 'password' ? `, ${value}` : ''}`} onClick={() => setEditing(true)}>
    <span className={`tv-field__value${shown ? '' : ' tv-field__value--empty'}`}>{shown || placeholder}</span>
  </button>
}
