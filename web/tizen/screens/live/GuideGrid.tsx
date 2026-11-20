import { memo, type CSSProperties } from 'react'
import type { LiveGuideChannel, LiveProgramme } from '../../../src/api/liveTV'
import { HOUR, episodeLine, isOn, layoutCells, programmeLabel, progress, shortDayTime, timeRange } from '../../../src/lib/liveGuide'
import { markLook, recordingMarkLabel } from '../../../src/lib/liveRecordings'
import { ChannelLogo } from './ChannelArt'
import { GUIDE_HOURS, targetId } from './guideModel'
import { LiveIcon, RecordingMark } from './LiveIcon'

const ms = (iso: string) => Date.parse(iso)

/** A cell's second line: the episode, a film's year, or its times. */
function cellLine(p: LiveProgramme): string {
  if (p.kind === 'movie') return `Movie${p.airDate ? ` · ${p.airDate.slice(0, 4)}` : ''}`
  return episodeLine(p) || timeRange(ms(p.start), ms(p.end))
}

function Tags({ p }: { p: LiveProgramme }) {
  return <>
    {p.new && <span className="live-tg live-tg--new">New</span>}
    {p.live && <span className="live-tg live-tg--live">Live</span>}
    {p.hd && <span className="live-tg">HD</span>}
  </>
}

export type GuideRowProps = {
  row: LiveGuideChannel
  start: number
  /** The minute now, so the row redraws on the minute and not on every focus move. */
  now: number
  favourite: boolean
  canRecord: boolean
  /** The cell the screen's focus starts on (fixed once the guide first draws). */
  autofocus?: string
}

/**
 * One channel across the two hours: its cell (OK watches it, Hold OK its
 * menu), then its programmes sized by the time they cover. Drawn again
 * only when its listings, the window, the minute or its star change: focus
 * shows by :focus alone.
 */
export const GuideRow = memo(function GuideRow({ row, start, now, favourite, canRecord, autofocus }: GuideRowProps) {
  const end = start + GUIDE_HOURS * HOUR
  const on = row.programmes.find((p) => isOn(p, now))
  if (!row.available) return <div className="live-grow live-grow--off">
    <div className="live-chc live-chc--off"><span className="live-chc__text"><b>{row.number}</b><span>{row.name}</span></span></div>
    <div className="live-track"><span className="live-cell live-cell--off" style={{ width: '100%' }}><span className="live-cell__title">Unavailable</span><span className="live-cell__sub">Not in the tuner’s last channel list</span></span></div>
  </div>
  return <div className="live-grow">
    <button type="button" className="live-chc" data-focus={targetId({ kind: 'channel', channelId: row.id })} data-autofocus={autofocus === targetId({ kind: 'channel', channelId: row.id }) || undefined}
      aria-label={`Watch ${row.number} ${row.name}${favourite ? ', favourite' : ''}${on ? `, ${on.title}` : ''}`}>
      <span className="live-chc__text"><span className="live-chc__num"><b>{row.number}</b>{favourite && <LiveIcon name="starFilled" className="live-chc__star"/>}</span><span>{row.name}</span></span>
      <ChannelLogo channel={row} className="live-chc__logo"/>
    </button>
    <div className="live-track">
      {!row.listings ? <button type="button" className="live-cell" style={{ width: '100%' }} data-focus={targetId({ kind: 'none', channelId: row.id })} data-autofocus={autofocus === targetId({ kind: 'none', channelId: row.id }) || undefined} aria-label={`No listings, ${row.number} ${row.name} plays live`}>
        <span className="live-cell__title">No listings</span><span className="live-cell__sub">Plays live</span>
      </button> : layoutCells(row.programmes, start, end).map((cell) => {
        const style: CSSProperties = { width: `${cell.width}%` }
        if (cell.kind === 'gap') {
          return <span key={`gap-${cell.start}`} className="live-cell live-cell--end" style={style}>
            {cell.listingsEnd !== undefined && <span className="live-cell__sub">No listings after {shortDayTime(cell.listingsEnd, now)}</span>}
          </span>
        }
        const p = cell.programme
        const airing = isOn(p, now)
        const look = canRecord ? markLook(p.recording) : undefined
        const id = targetId({ kind: 'programme', channelId: row.id, programmeId: p.id })
        return <button key={p.id} type="button" className={`live-cell${airing ? ' live-cell--now' : ''}`} style={style} data-focus={id} data-autofocus={autofocus === id || undefined}
          aria-label={`${programmeLabel(p)}${canRecord ? recordingMarkLabel(p.recording) : ''}${p.reminder ? ', reminder set' : ''}`}>
          <span className="live-cell__title">
            {p.reminder && <LiveIcon name="bell" className="live-cell__bell"/>}
            {look && <RecordingMark look={look}/>}
            {cell.before ? '‹ ' : ''}{p.title}
          </span>
          <span className="live-cell__sub"><Tags p={p}/>{cellLine(p)}</span>
          {airing && <span className="live-cell__progress" style={{ width: `${progress(p, now)}%` }}/>}
        </button>
      })}
    </div>
  </div>
})

/** The time across the top: the day, then four half hours. */
export const GuideHeader = memo(function GuideHeader({ day, slots }: { day: string; slots: string[] }) {
  return <div className="live-ghead" aria-hidden="true">
    <span className="live-ghead__day">{day}</span>
    {slots.map((s) => <span key={s} className="live-ghead__slot">{s}</span>)}
  </div>
})
