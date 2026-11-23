import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { playbackPlansQuery } from '../api/queries'
import type { components } from '../api/schema'
import { profile } from '../lib/capabilities'

type Profile = components['schemas']['Profile']
type PlaybackPlan = components['schemas']['PlaybackPlan']

/** This browser's playback profile, once it is known. */
function useProfile(): Profile | undefined {
  const [p, setP] = useState<Profile>()
  useEffect(() => {
    let live = true
    void profile().then((x) => {
      if (live) setP(x)
    })
    return () => {
      live = false
    }
  }, [])
  return p
}

/** How each file would play on this browser, by file id; empty until the server says. */
export function usePlans(fileIds: number[]): Map<number, PlaybackPlan> {
  const p = useProfile()
  const plans = useQuery({ ...playbackPlansQuery(fileIds, p ?? ({ name: 'web' } as Profile)), enabled: !!p && fileIds.length > 0 })
  const out = new Map<number, PlaybackPlan>()
  for (const plan of plans.data?.items ?? []) out.set(plan.fileId, plan)
  return out
}
