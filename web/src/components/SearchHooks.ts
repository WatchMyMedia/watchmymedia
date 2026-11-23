import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { liveTVSearchQuery } from '../api/queries'
import { requestOptionsQuery, requestableSearchQuery } from '../api/requests'
import { searchTerm } from '../lib/liveReminders'
import { notInLibrary } from '../lib/searchPage'
import { useGuideExtras } from './liveTV/useGuideExtras'

/** The live TV guide's programmes whose titles have the words, when the server has listings. */
export function useLiveSearch(q: string) {
  const extras = useGuideExtras()
  const term = searchTerm(q)
  const results = useQuery({ ...liveTVSearchQuery(term ?? ''), enabled: extras && !!term, placeholderData: keepPreviousData })
  return { on: extras && !!term, hits: extras && term ? (results.data?.items ?? []) : [], pending: results.isPending && extras && !!term }
}

/** What TMDB has that the library doesn't, when this person may request. */
export function useNotInLibrary(q: string) {
  const options = useQuery(requestOptionsQuery)
  const on = !!(options.data?.movies || options.data?.shows) && q.length > 0
  const results = useQuery({ ...requestableSearchQuery(q), enabled: on })
  const items = on && results.data ? notInLibrary(results.data.items, { movies: options.data?.movies, shows: options.data?.shows }) : []
  return { on, items, backend: options.data?.backend, pending: on && results.isPending, error: results.isError ? results.error : undefined }
}
