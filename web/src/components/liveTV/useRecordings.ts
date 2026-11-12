import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { invalidateRecordings, liveTVCapabilitiesQuery, serverQuery } from '../../api/queries'
import { canRecord, serverRecords } from '../../lib/liveRecordings'

/** Whether this person may record, and whether that is still being asked (so a page can wait rather than say no). */
export function useRecordingAccess() {
  const server = useQuery(serverQuery)
  const caps = useQuery(liveTVCapabilitiesQuery)
  return { allowed: canRecord(server.data?.apiLevel, caps.data), pending: server.isPending || caps.isPending }
}
export function useCanRecord() {
  return useRecordingAccess().allowed
}
/** The server records for someone: a recording can then take a tuner someone is watching on. */
export function useServerRecords() {
  const server = useQuery(serverQuery)
  const caps = useQuery(liveTVCapabilitiesQuery)
  return serverRecords(server.data?.apiLevel, caps.data)
}
export function useRecordingMutation<T, V>(mutationFn: (variables: V) => Promise<T>) {
  const qc = useQueryClient()
  return useMutation({ mutationFn, onSettled: () => invalidateRecordings(qc) })
}
