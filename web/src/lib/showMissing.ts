import { useState } from 'react'

/** Show missing is remembered per show, in this browser only; the show page and the episode page share it. */
export function useShowMissing(showId: number): [boolean, (on: boolean) => void] {
  const key = `wmm.showMissing.${showId}`
  const [on, setOn] = useState(() => {
    try {
      return localStorage.getItem(key) === '1'
    } catch {
      return false
    }
  })
  return [
    on,
    (v: boolean) => {
      setOn(v)
      try {
        if (v) localStorage.setItem(key, '1')
        else localStorage.removeItem(key)
      } catch {
        // Private windows may refuse storage; the switch still works for the visit.
      }
    },
  ]
}
