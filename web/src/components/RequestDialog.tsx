import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { requestOptionsQuery, requestableQuery, useCreateRequest, type MediaRequest } from '../api/requests'
import { requestButtonLabel } from '../lib/requests'
import { Dialog } from './Dialog'
import { Poster } from './Poster'

const seasonState: Record<string, string> = {
  available: 'In library',
  partial: 'Partly in library',
  requested: 'Requested',
  unaired: 'Not aired yet',
  missing: '',
}

/**
 * Asks for a movie, or some seasons of a show (PLAN-ARR.md §7.1). preselect
 * ticks seasons, as the show page's Request season does; otherwise every
 * season that can be asked for starts ticked.
 */
export function RequestDialog({ mediaType, tmdbId, preselect, onClose }: { mediaType: 'movie' | 'tv'; tmdbId: number; preselect?: number[]; onClose: () => void }) {
  const item = useQuery(requestableQuery(mediaType, tmdbId))
  const options = useQuery(requestOptionsQuery)
  const create = useCreateRequest()
  const [picked, setPicked] = useState<number[] | null>(null)
  const [sent, setSent] = useState<{ created: boolean; request: MediaRequest } | null>(null)
  const r = item.data
  const askable = (r?.seasons ?? []).filter((s) => s.state === 'missing' || s.state === 'partial')
  const chosen = picked ?? (preselect ?? askable.map((s) => s.number)).filter((n) => askable.some((s) => s.number === n))

  return (
    <Dialog title={r ? `${r.title}${r.year ? ` (${r.year})` : ''}` : 'Request'} onClose={onClose}>
      {item.isPending && <p className="px-6 py-5 text-sm text-neutral-400">Loading…</p>}
      {item.isError && <p className="px-6 py-5 text-sm text-red-400">{item.error.message}</p>}
      {r && (
        <div className="px-6 pb-6 pt-4">
          <div className="flex gap-4">
            <div className="w-24 shrink-0">
              <Poster path={r.posterPath} alt={r.title} size="w185" />
            </div>
            <p className="line-clamp-6 text-sm text-neutral-300">{r.overview}</p>
          </div>

          {sent ? (
            <div role="status" className="mt-5 space-y-3 text-sm">
              {sent.created || sent.request.mine ? (
                <p>
                  {options.data?.backend === 'seerr' ? 'Sent to Seerr.' : 'Requested.'} You&rsquo;ll find it in{' '}
                  <Link to="/settings/requests" className="underline">
                    Your requests
                  </Link>
                  .
                </p>
              ) : (
                <p>{sent.request.requestedBy} asked for this already. It&rsquo;s in their requests, and you&rsquo;ll see it here when it arrives.</p>
              )}
              <button type="button" className="min-h-[40px] rounded-md border border-line-2 px-3.5 text-sm hover:bg-surface-2" onClick={onClose}>
                Done
              </button>
            </div>
          ) : (
            <form
              className="mt-5 space-y-4"
              onSubmit={(e) => {
                e.preventDefault()
                create.mutate({ mediaType, tmdbId, seasons: mediaType === 'tv' ? chosen : undefined }, { onSuccess: setSent })
              }}
            >
              {r.seasons && (
                <fieldset className="space-y-1">
                  <legend className="mb-2 text-sm font-semibold">Seasons</legend>
                  {r.seasons.map((s) => {
                    const can = s.state === 'missing' || s.state === 'partial'
                    return (
                      <label key={s.number} className={`flex min-h-[44px] items-center gap-3 rounded-md px-2 ${can ? 'cursor-pointer hover:bg-surface-2' : 'opacity-60'}`}>
                        <input
                          type="checkbox"
                          className="h-[18px] w-[18px] accent-accent"
                          disabled={!can}
                          checked={chosen.includes(s.number) || s.state === 'available' || s.state === 'requested'}
                          onChange={(e) => setPicked(e.target.checked ? [...chosen, s.number] : chosen.filter((n) => n !== s.number))}
                        />
                        <span className="flex-1 text-sm">
                          {s.number === 0 ? 'Specials' : s.name || `Season ${s.number}`}
                          <span className="ml-2 text-neutral-400">{s.episodeCount} episodes</span>
                        </span>
                        {seasonState[s.state] && <span className="text-xs text-neutral-400">{seasonState[s.state]}</span>}
                      </label>
                    )
                  })}
                </fieldset>
              )}
              {r.state === 'available' && mediaType === 'movie' ? (
                <p className="text-sm text-neutral-300">It&rsquo;s in your library already.</p>
              ) : (
                <>
                  {options.data?.needsApproval && (
                    <p className="text-sm text-neutral-400">
                      An admin approves requests{options.data.backend === 'seerr' ? ' in Seerr' : ''} before anything downloads.
                    </p>
                  )}
                  {create.isError && <p className="text-sm text-warn">{create.error.message}</p>}
                  <div className="flex flex-wrap gap-2">
                    <button
                      type="submit"
                      className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50"
                      disabled={create.isPending || (mediaType === 'tv' && chosen.length === 0) || r.state === 'requested' || r.state === 'downloading'}
                    >
                      {r.state === 'requested' || r.state === 'downloading' ? 'Requested' : requestButtonLabel(mediaType, chosen)}
                    </button>
                    <button type="button" className="min-h-[40px] rounded-md border border-line-2 px-3.5 text-sm hover:bg-surface-2" onClick={onClose}>
                      Cancel
                    </button>
                  </div>
                </>
              )}
            </form>
          )}
        </div>
      )}
    </Dialog>
  )
}
