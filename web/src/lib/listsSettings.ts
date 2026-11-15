import type { RowLayoutEntry } from './rowLayout'

// Settings › Server › Lists (PLAN-ADMIN-SETTINGS.md §5.9): default rows are
// a draft with the save bar, and a save refused for a row that no longer
// exists takes that row out of the draft.

/** The row a 422 names: `this screen has no row "list:12"`. */
export function missingRowId(message: string): string | undefined {
  return /has no row "([^"]+)"/.exec(message)?.[1]
}

export type ListsSaveError = { title: string; body: string; drop?: string }

/** Why Save was refused, worded for the page, and the row to drop from the draft when it is gone. */
export function rowsSaveError(error: unknown, rows: readonly Pick<RowLayoutEntry, 'id' | 'title'>[], words: (e: unknown) => string): ListsSaveError {
  const status = error && typeof error === 'object' && 'status' in error ? (error as { status: number }).status : 0
  const message = error instanceof Error ? error.message : ''
  const id = status === 422 ? missingRowId(message) : undefined
  const row = id ? rows.find((r) => r.id === id) : undefined
  if (id) {
    return {
      title: `Couldn’t save: “${row?.title ?? 'a row'}” no longer exists`,
      body: 'That list was deleted while you were here. It’s been taken out of the draft. Save again.',
      drop: id,
    }
  }
  return { title: 'Couldn’t save the default rows', body: `${words(error).replace(/\.$/, '')}. The draft is still here.` }
}

/** The MDBList key's refusal: MDBList said no (422), or couldn't be reached (503). */
export function mdblistSaveError(error: unknown, words: (e: unknown) => string): { title: string; body: string } {
  const status = error && typeof error === 'object' && 'status' in error ? (error as { status: number }).status : 0
  if (status === 422) return { title: 'MDBList didn’t accept this key', body: 'Copy the API key from your MDBList preferences again.' }
  if (status === 503) return { title: 'Couldn’t reach MDBList, so the key wasn’t saved', body: 'Check the server’s internet connection and save again.' }
  return { title: 'The key wasn’t saved', body: words(error) }
}
