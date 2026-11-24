import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, useUpdatePreferences } from '../api/queries'

/**
 * Hide spoilers, the person's choice (API level 34, kept with their
 * preferences so it follows them to every device): the switch shows the
 * new value while it saves.
 */
export function useHideSpoilers(): { on: boolean; set: (on: boolean) => void; error?: string } {
  const prefs = useQuery(preferencesQuery)
  const update = useUpdatePreferences()
  const saved = !!prefs.data?.hideSpoilers
  const on = update.isPending && update.variables?.hideSpoilers !== undefined ? update.variables.hideSpoilers : saved
  return { on, set: (next) => update.mutate({ hideSpoilers: next }), error: update.error?.message }
}
