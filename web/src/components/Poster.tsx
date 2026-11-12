import { useState } from 'react'
import { img, type ImageSize } from '../lib/images'

/** Poster-shaped image with a quiet placeholder when there is no artwork. */
export function Poster({
  path,
  size = 'w342',
  alt,
  className = '',
  ratio = 'aspect-[2/3]',
}: {
  path?: string
  size?: ImageSize
  alt: string
  className?: string
  ratio?: string
}) {
  const [failed, setFailed] = useState(false)
  const src = failed ? undefined : img(path, size)
  return (
    <div className={`${ratio} w-full overflow-hidden rounded-md bg-neutral-800 ${className}`}>
      {src ? (
        <img src={src} alt={alt} loading="lazy" onError={() => setFailed(true)} className="h-full w-full object-cover" />
      ) : (
        <div className="flex h-full w-full items-center justify-center p-2 text-center text-xs text-neutral-500">{alt}</div>
      )}
    </div>
  )
}
