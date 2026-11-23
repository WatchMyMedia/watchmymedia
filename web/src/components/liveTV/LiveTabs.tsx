import type { ReactNode } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVRecordingsQuery } from '../../api/queries'
import { useCanRecord } from './useRecordings'

/**
 * Live TV's heading and its two tabs, Guide and Recordings; Recordings says
 * when something is recording now. right holds the page's own action.
 */
export function LiveTabs({ active, right }: { active: 'guide' | 'recordings'; right?: ReactNode }) {
  const canRecord = useCanRecord()
  const now = useInfiniteQuery({ ...liveTVRecordingsQuery('now'), enabled: canRecord })
  const recording = now.data?.pages.flatMap((p) => p.items).filter((r) => r.state === 'recording').length ?? 0
  return (
    <>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <h1 className="text-[28px] font-bold tracking-[-.02em]">Live TV</h1>
        {right && <div className="ml-auto flex items-center gap-2">{right}</div>}
      </div>
      <nav aria-label="Live TV" className="tabs mt-2">
        <Link to="/live-tv" aria-current={active === 'guide' ? 'page' : undefined} className="tab">
          Guide
        </Link>
        {canRecord && (
          <Link to="/live-tv/recordings" aria-current={active === 'recordings' ? 'page' : undefined} className="tab">
            Recordings
            {recording > 0 && (
              <>
                <span aria-hidden="true" className="size-2 rounded-full bg-red-400" />
                <span className="n">{recording} recording now</span>
              </>
            )}
          </Link>
        )}
      </nav>
    </>
  )
}
