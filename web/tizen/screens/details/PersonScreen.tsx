import { type CSSProperties } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { PersonTitle } from '../../../src/api/people'
import { routeOn, SourceContext, useMediaUrls, useNav, usePageSource, useSource } from '../../app/hooks'
import { personOn } from '../../session/sourceQueries'
import type { Source } from '../../session/peerRegistry'
import { SourceGone } from './MovieScreen'
import type { ScreenProps } from '../../app/routes'
import { Card } from '../../ui/Card'
import { Clock } from '../../ui/Clock'
import { Row } from '../../ui/Row'
import { ProblemScreen, Skeleton, SkeletonRow, SkeletonText } from '../../ui/States'
import { VScroll } from '../../ui/VScroll'
import { problemWords, statusOf } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'
import { Picture } from './DetailParts'
import { initials } from './detailWords'
import { personLine, personTitleLine, personTitleRoute } from './personWords'
import './details.css'

/**
 * A cast member's movies and shows on this server (route `person`, from a
 * cast card on a title's page): their picture and name, how many titles of
 * theirs are here, then a row of movies and a row of shows, each with the
 * part they played. No menu; focus starts on the first title, and Return
 * goes back to the title with focus on that person.
 */
export function PersonScreen({ route }: ScreenProps<'person'>) {
  const source = useSource(route.server)
  if (source === 'gone') return <SourceGone/>
  return <SourceContext.Provider value={source}><PersonFrom route={route} source={source}/></SourceContext.Provider>
}

/** A person's titles on `source`: the current server, or the one the title they were in came from. */
function PersonFrom({ route, source }: ScreenProps<'person'> & { source: Source }) {
  const person = useQuery(personOn(source, route.personId))
  const nav = useNav()
  if (person.isPending) return <PersonLoading name={route.who}/>
  if (person.isError) {
    const gone = statusOf(person.error) === 404
    const words = problemWords(person.error)
    return <div className="dt-page"><ProblemScreen title={gone ? `Nothing with ${route.who ?? 'them'} is here now` : `Couldn’t load ${route.who ?? 'this person'}’s titles`} actions={[
      ...(gone ? [] : [{ id: 'retry', label: 'Try again', onPress: () => void person.refetch() }]),
      { id: 'back', label: 'Back', onPress: () => nav.back() },
    ]}>{gone ? 'Their titles may have left the library.' : `${words.title}. ${words.body}`}</ProblemScreen></div>
  }
  return <PersonPage name={person.data.name} profilePath={person.data.profilePath} movies={person.data.movies} shows={person.data.shows}/>
}

function PersonLoading({ name }: { name?: string }) {
  return <div className="dt-page person" aria-busy="true">
    <span className="tv-sr" role="status">Loading {name ?? 'their'} titles</span>
    <header className="person-head" aria-hidden="true">
      <Skeleton width="11rem" height="11rem" radius="50%"/>
      <div className="person-head__text"><SkeletonText width="6rem"/>{name ? <h1 className="tv-display">{name}</h1> : <Skeleton width="30rem" height="4.5rem"/>}<SkeletonText width="28rem"/></div>
    </header>
    <div className="person-rows" aria-hidden="true"><SkeletonRow variant="poster"/></div>
  </div>
}

function PersonPage({ name, profilePath, movies, shows }: { name: string; profilePath?: string; movies: PersonTitle[]; shows: PersonTitle[] }) {
  const source = usePageSource()
  const media = useMediaUrls()
  const nav = useNav()
  const [a, b] = placeholderTone(name)
  const row = (id: string, title: string, items: PersonTitle[], first: boolean) => items.length > 0 && <div className="person-row" data-vitem data-autofocus={first || undefined}>
    <Row id={id} title={title} variant="poster" items={items} itemKey={(t) => `${t.kind}${t.id}`}
      renderItem={(t, focusId) => {
        const at = personTitleRoute(t)
        const to = at && routeOn(at, source)
        const line = personTitleLine(t)
        return <Card id={focusId} variant="poster" title={t.title} subtitle={line} image={media.imageUrl(t.posterPath, 'w342')}
          progress={t.progress} done={!!t.watched && !t.progress} label={[t.title, line, t.watched && 'watched'].filter(Boolean).join(', ')}
          onPress={to && (() => nav.push(to))}/>
      }}/>
  </div>
  return <div className="dt-page person">
    <Clock/>
    <VScroll pad={54} className="person-scroll">
      <header className="person-head">
        <span className="person-head__pic" style={{ '--ph-a': a, '--ph-b': b } as CSSProperties} aria-hidden="true">
          <span className="tv-person__initials">{initials(name)}</span>
          <Picture src={media.imageUrl(profilePath, 'w185')}/>
        </span>
        <div className="person-head__text">
          <p className="tv-kicker">Cast</p>
          <h1 className="tv-display person-head__name">{name}</h1>
          <p className="person-head__line">{personLine(movies.length, shows.length, source?.name ?? '')}</p>
        </div>
      </header>
      {row('person/movies', 'Movies', movies, true)}
      {row('person/shows', 'TV Shows', shows, movies.length === 0)}
    </VScroll>
  </div>
}
