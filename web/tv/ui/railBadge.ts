import type { RequestOptions } from '../../src/api/requests'

/**
 * The number on the rail's Settings: an admin's Inbox (requests waiting
 * for approval plus open problems, RequestOptions.inbox), as on Android TV.
 * Members see none; their requests are a page in Settings.
 */
export function railBadge(options: Pick<RequestOptions, 'inbox' | 'manage'> | undefined, isAdmin: boolean): number | undefined {
  if (!options || !isAdmin) return undefined
  return options.inbox > 0 ? options.inbox : undefined
}
