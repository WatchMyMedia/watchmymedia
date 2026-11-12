import { useState } from 'react'
import { useReportProblem, type Problem } from '../api/requests'
import { Dialog } from './Dialog'

const kinds: { id: Problem['kind']; label: string }[] = [
  { id: 'video', label: 'Video' },
  { id: 'audio', label: 'Audio' },
  { id: 'subtitles', label: 'Subtitles' },
  { id: 'other', label: 'Other' },
]

/** Reports a problem with a movie or episode to the admins' Inbox, or as a Seerr issue when Seerr takes it (PLAN-ARR.md §9). */
export function ReportProblemDialog({ itemType, itemId, fileId, title, onClose }: { itemType: 'movie' | 'episode'; itemId: number; fileId?: number; title: string; onClose: () => void }) {
  const [kind, setKind] = useState<Problem['kind'] | null>(null)
  const [note, setNote] = useState('')
  const report = useReportProblem()
  return (
    <Dialog title="Report a problem" subtitle={title} onClose={onClose}>
      {report.isSuccess ? (
        <div role="status" className="space-y-3 px-6 pb-6 pt-4 text-sm">
          <p>{report.data?.seerrIssueId ? 'Sent to Seerr as an issue. Admins hear about it as Seerr is set up to tell them.' : 'Sent. An admin will see it in their Inbox.'}</p>
          <button type="button" className="min-h-[40px] rounded-md border border-line-2 px-3.5 text-sm hover:bg-surface-2" onClick={onClose}>
            Done
          </button>
        </div>
      ) : (
        <form
          className="space-y-4 px-6 pb-6 pt-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (kind) report.mutate({ itemType, itemId, fileId, kind, note: note.trim() || undefined })
          }}
        >
          <fieldset>
            <legend className="mb-2 text-sm font-semibold">What&rsquo;s wrong?</legend>
            <div className="flex flex-wrap gap-2" role="radiogroup">
              {kinds.map((k) => (
                <label
                  key={k.id}
                  className={`flex min-h-[40px] cursor-pointer items-center rounded-md border px-3.5 text-sm ${kind === k.id ? 'border-accent text-accent' : 'border-line-2 hover:bg-surface-2'}`}
                >
                  <input type="radio" name="problem-kind" value={k.id} checked={kind === k.id} onChange={() => setKind(k.id)} className="sr-only" />
                  {k.label}
                </label>
              ))}
            </div>
          </fieldset>
          <label className="flex flex-col gap-1 text-sm font-semibold">
            Note
            <textarea
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={3}
              maxLength={1000}
              className="rounded-md border border-line-2 bg-ground px-3 py-2 text-sm font-normal text-neutral-100"
            />
          </label>
          <p className="text-sm text-neutral-400">We&rsquo;ll attach the file name, codecs and resolution.</p>
          {report.isError && <p className="text-sm text-warn">{report.error.message}</p>}
          <div className="flex gap-2">
            <button type="submit" disabled={!kind || report.isPending} className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50">
              Send report
            </button>
            <button type="button" className="min-h-[40px] rounded-md border border-line-2 px-3.5 text-sm hover:bg-surface-2" onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
