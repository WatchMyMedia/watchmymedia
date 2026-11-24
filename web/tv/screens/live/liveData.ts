import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { invalidateRecordings, liveTVCapabilitiesQuery, liveTVChannelsQuery } from '../../../src/api/queries'
import { canRecord as mayRecord } from '../../../src/lib/liveRecordings'
import { useSession } from '../../app/hooks'

/** Capabilities and channels, as every Live TV screen gates on them: can this account watch here, and what. */
export function useLiveTV() {
  const session = useSession()
  const caps = useQuery(liveTVCapabilitiesQuery)
  const available = !!caps.data?.enabled && !!caps.data.allowed
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: available })
  return {
    caps, channels, available,
    listings: !!caps.data?.guide,
    canRecord: mayRecord(session.server.apiLevel, caps.data),
  }
}

/** A recording change, refreshing every list and mark it touches (the web's invalidateRecordings). */
export function useRecordingAction<T, V>(mutationFn: (variables: V) => Promise<T>) {
  const qc = useQueryClient()
  return useMutation({ mutationFn, onSettled: () => invalidateRecordings(qc) })
}
