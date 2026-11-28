import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, useUpdatePreferences } from '../api/queries'
import { changeSound, soundOf, type Sound, type SoundLevel, type SoundSetting } from '../lib/sound'
import type { SoundChange } from './PlayerPanels'

/**
 * Boost dialogue and Reduce loud sounds changed in a player: save the
 * person's setting, then have the play go on from where it is with it
 * (apply: the local player's reload, or the TV's). The row is busy until
 * then, and says it saved; a save the server refused puts the old value
 * back and says so.
 */
export function useSoundChange(apply: () => Promise<unknown>): { sound: Sound; change: SoundChange; pick: (setting: SoundSetting, level: SoundLevel) => void; retry: () => void } {
  const prefs = useQuery(preferencesQuery).data
  const update = useUpdatePreferences()
  const [pending, setPending] = useState<{ setting: SoundSetting; level: SoundLevel } | null>(null)
  const [change, setChange] = useState<SoundChange>(null)
  const last = useRef<{ setting: SoundSetting; level: SoundLevel } | null>(null)
  const latestApply = useRef(apply)
  useEffect(() => { latestApply.current = apply })
  const saved = soundOf(prefs)

  // "Saved" stays a few seconds, then the row is just its value.
  useEffect(() => {
    if (change?.phase !== 'ok') return
    const timer = window.setTimeout(() => setChange((c) => (c === change ? null : c)), 6000)
    return () => window.clearTimeout(timer)
  }, [change])

  const pick = (setting: SoundSetting, level: SoundLevel) => {
    if (change?.phase === 'busy') return
    last.current = { setting, level }
    setPending({ setting, level })
    void changeSound(setting, level, saved, {
      save: (patch) => update.mutateAsync(patch),
      apply: () => latestApply.current(),
      report: (state) => {
        if (state.phase !== 'busy') setPending(null)
        setChange(state)
      },
    })
  }

  return {
    sound: pending ? { ...saved, [pending.setting]: pending.level } : saved,
    change,
    pick,
    retry: () => last.current && pick(last.current.setting, last.current.level),
  }
}
