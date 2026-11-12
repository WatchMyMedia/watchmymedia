import type { RowLayoutEntry, RowPlacement } from '../../lib/rowLayout'

/**
 * Edit rows' unsaved work, kept for the visit so leaving for Make a list and
 * coming back finds it as it was (a reload starts again). restore is the
 * order Reset to default replaced, put back if the person then cancels.
 */
export type RowDraft = { rows: RowLayoutEntry[] | null; restore?: RowPlacement[] }

const drafts = new Map<string, RowDraft>()

export function readRowDraft(key: string): RowDraft {
  return drafts.get(key) ?? { rows: null }
}

export function writeRowDraft(key: string, draft: RowDraft | null) {
  if (!draft || (!draft.rows && !draft.restore)) drafts.delete(key)
  else drafts.set(key, draft)
}
