import { memo, type CSSProperties } from 'react'
import type { LiveGuideChannel, LiveProgramme } from '../../../src/api/liveTV'
import { HOUR, isOn, layoutCells, programmeLabel, shortDayTime } from '../../../src/lib/liveGuide'
import { markLook, recordingMarkLabel } from '../../../src/lib/liveRecordings'
import { GUIDE_HOURS, cellLine, targetId } from './guideModel'
import { LiveIcon } from './LiveIcon'

/** The red dot of what will record, ringed for a series; REC while it records; an orange ring when it won't. */
function Mark({ p, canRecord }: { p: LiveProgramme; canRecord: boolean }) {
  const look = canRecord ? markLook(p.recording) : undefined
  if (!look) return p.reminder ? <LiveIcon name="bell" className="live-cell__bell"/> : null
  if (look === 'rec') return <span className="live-cell__rec">REC</span>
  return <span className={`live-mark live-mark--${look}`} aria-hidden="true"/>
}

export type GuideRowProps = {
  row: LiveGuideChannel
  /** Its place among the rows drawn, top first. */
  index: number
  start: number
  /** The minute now, so the row redraws on the minute and not on every focus move. */
  now: number
  favourite: boolean
  canRecord: boolean
  /** The cell the screen's focus starts on (fixed once the guide first draws). */
  autofocus?: string
}

/**
 * One channel across the two hours: its number and name (not a focus
 * stop), then its programmes sized by the time they cover. Drawn again
 * only when its listings, the window, the minute or its star change: focus
 * shows by :focus alone.
 */
export const GuideRow = memo(function GuideRow({ row, index, start, now, favourite, canRecord, autofocus }: GuideRowProps) {
  const end = start + GUIDE_HOURS * HOUR
  const style = { '--row': index } as CSSProperties
  const channel = <div className="live-chc">
    <span className="live-chan">{row.number}</span>
    <span className="live-chc__text"><b>{row.name}</b>{(favourite || !row.available) && <span>{row.available ? 'Favourite' : 'Not available'}</span>}</span>
  </div>
  if (!row.available) return <div className="live-grow live-grow--off" style={style}>
    {channel}
    <div className="live-track"><span className="live-cell live-cell--off" style={{ left: 0, width: 'calc(100% - .375rem)' }}><span className="live-cell__title">Unavailable</span><span className="live-cell__sub">Not in the tuner’s last channel list</span></span></div>
  </div>
  return <div className="live-grow" style={style}>
    {channel}
    <div className="live-track">
      {!row.listings ? <button type="button" className="live-cell" style={{ left: 0, width: 'calc(100% - .375rem)' }} data-focus={targetId({ kind: 'none', channelId: row.id })} data-autofocus={autofocus === targetId({ kind: 'none', channelId: row.id }) || undefined} aria-label={`No listings, ${row.number} ${row.name} plays live`}>
        <span className="live-cell__title">No listings</span><span className="live-cell__sub">It still plays</span>
      </button> : layoutCells(row.programmes, start, end).map((cell) => {
        const place: CSSProperties = { left: `${cell.left}%`, width: `calc(${cell.width}% - .375rem)` }
        if (cell.kind === 'gap') {
          return <span key={`gap-${cell.start}`} className="live-cell live-cell--end" style={place}>
            {cell.listingsEnd !== undefined && <span className="live-cell__sub">No listings after {shortDayTime(cell.listingsEnd, now)}</span>}
          </span>
        }
        const p = cell.programme
        const id = targetId({ kind: 'programme', channelId: row.id, programmeId: p.id })
        return <button key={p.id} type="button" className={`live-cell${isOn(p, now) ? ' live-cell--now' : ''}`} style={place} data-focus={id} data-autofocus={autofocus === id || undefined}
          aria-label={`${programmeLabel(p)}${canRecord ? recordingMarkLabel(p.recording) : ''}${p.reminder ? ', reminder set' : ''}`}>
          <span className="live-cell__title">{p.title}</span>
          <span className="live-cell__sub"><Mark p={p} canRecord={canRecord}/><span>{cellLine(p, start)}</span></span>
        </button>
      })}
    </div>
  </div>
})

/** The time across the top: the evening, then four half hours, and Now where it falls. */
export const GuideHeader = memo(function GuideHeader({ day, slots, now }: { day: string; slots: string[]; now?: number }) {
  return <div className="live-ghead" aria-hidden="true">
    <span className="live-ghead__day">{day}</span>
    {slots.map((s, i) => <span key={s} className="live-ghead__slot" style={{ left: `calc(var(--live-chc-w) + var(--live-track-w) * ${i / slots.length})` }}>{s}</span>)}
    {now !== undefined && <span className="live-ghead__now" style={{ left: `calc(var(--live-chc-w) + var(--live-track-w) * ${now.toFixed(4)} + .75rem)` }}>Now</span>}
  </div>
})
