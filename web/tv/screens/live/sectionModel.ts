// Live TV's tabs: the guide, Recordings for those who may record, and
// Reminders while the server has listings (reminders are set on its
// programmes). Words only; LiveSection.tsx draws them.

export type LiveTab = 'guide' | 'recordings' | 'reminders'

export function liveTabs({ canRecord, listings }: { canRecord: boolean; listings: boolean }): { id: LiveTab; label: string }[] {
  return [
    { id: 'guide', label: 'Guide' },
    ...(canRecord ? [{ id: 'recordings' as const, label: 'Recordings' }] : []),
    ...(listings ? [{ id: 'reminders' as const, label: 'Reminders' }] : []),
  ]
}
