import { describe, expect, it } from 'vitest'
import { liveTabs } from './sectionModel'

describe('Live TV’s tabs', () => {
  it('shows Recordings to those who may record, and Reminders with listings', () => {
    expect(liveTabs({ canRecord: true, listings: true }).map((t) => t.label)).toEqual(['Guide', 'Recordings', 'Reminders'])
    expect(liveTabs({ canRecord: false, listings: true }).map((t) => t.id)).toEqual(['guide', 'reminders'])
    expect(liveTabs({ canRecord: true, listings: false }).map((t) => t.label)).toEqual(['Guide', 'Recordings'])
  })
})
