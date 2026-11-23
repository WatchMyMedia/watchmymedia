import { useEffect, useSyncExternalStore } from 'react'
import { Icon } from './Icon'
import { clearNote, currentNote, subscribe } from './notes'

/** Draws the note, once for the app. */
export function Notes() {
  const note = useSyncExternalStore(subscribe, currentNote)
  useEffect(() => {
    if (!note) return
    const timer = setTimeout(() => { if (currentNote()?.n === note.n) clearNote() }, Math.max(0, note.until - Date.now()))
    return () => clearTimeout(timer)
  }, [note])
  return <p className="tv-toast" role="status" aria-live="polite" hidden={!note}>{note?.icon && <Icon name={note.icon}/>}{note?.text}</p>
}
