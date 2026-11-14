import { describe, expect, it } from 'vitest'
import { mdblistSaveError, missingRowId, rowsSaveError } from './listsSettings'

const words = (e: unknown) => (e instanceof Error ? `${e.message}.` : 'Something went wrong.')
const refusal = (status: number, message: string) => Object.assign(new Error(message), { status })

describe('default rows', () => {
  it('reads the row a refusal names', () => {
    expect(missingRowId('this screen has no row "list:12"')).toBe('list:12')
    expect(missingRowId('row "list:12" is placed twice')).toBeUndefined()
  })

  it('takes a deleted list out of the draft and says so', () => {
    const rows = [{ id: 'continue', title: 'Continue watching' }, { id: 'list:12', title: 'Staff picks' }]
    expect(rowsSaveError(refusal(422, 'this screen has no row "list:12"'), rows, words)).toEqual({
      title: 'Couldn’t save: “Staff picks” no longer exists',
      body: 'That list was deleted while you were here. It’s been taken out of the draft. Save again.',
      drop: 'list:12',
    })
  })

  it('keeps the draft for any other refusal', () => {
    expect(rowsSaveError(refusal(500, 'database is locked'), [], words)).toEqual({ title: 'Couldn’t save the default rows', body: 'database is locked. The draft is still here.' })
  })
})

describe('the MDBList key', () => {
  it('words MDBList’s refusals', () => {
    expect(mdblistSaveError(refusal(422, 'MDBList rejected the key'), words).title).toBe('MDBList didn’t accept this key')
    expect(mdblistSaveError(refusal(503, 'timeout'), words).title).toBe('Couldn’t reach MDBList, so the key wasn’t saved')
  })
})
