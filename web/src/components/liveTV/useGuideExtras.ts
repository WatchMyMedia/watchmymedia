import { useQuery } from '@tanstack/react-query'
import { liveTVCapabilitiesQuery, liveTVRemindersQuery, serverQuery } from '../../api/queries'
import { showGuideExtras } from '../../lib/liveGuide'
import { leadMinutes } from '../../lib/liveReminders'

/** Whether this person gets favourites, search and reminders: listings on a server at API level 15, and Live TV for them. */
export function useGuideExtras(): boolean {
  const server = useQuery(serverQuery)
  const caps = useQuery(liveTVCapabilitiesQuery)
  return showGuideExtras(server.data?.apiLevel, caps.data) && !!caps.data?.enabled && !!caps.data.allowed
}

/** This person's reminders, the lead time, and each programme's reminder by its id. */
export function useReminders(enabled: boolean) {
  const reminders = useQuery({ ...liveTVRemindersQuery, enabled })
  const items = reminders.data?.items ?? []
  const byProgramme = new Map(items.map((r) => [r.programmeId, r]))
  return { items, lead: leadMinutes(reminders.data?.leadMinutes), byProgramme, loaded: reminders.isSuccess }
}
