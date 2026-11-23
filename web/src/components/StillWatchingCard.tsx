/** Asked before the next episode starts, after a long run with no input. */
export function StillWatchingCard({ showTitle, onContinue, onBack }: { showTitle: string; onContinue: () => void; onBack: () => void }) {
  return (
    <div className="absolute inset-0 z-20 grid place-items-center bg-black/70 p-4" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-labelledby="still-title">
      <div className="w-full max-w-sm rounded-[14px] border border-line-3 bg-surface/95 p-5 text-white shadow-2xl">
        <h2 id="still-title" className="text-lg font-semibold">
          Are you still watching?
        </h2>
        <p className="mt-1 text-sm text-white/70">{showTitle}</p>
        <div className="mt-4 flex flex-col gap-2">
          <button type="button" onClick={onContinue} autoFocus className="btn btn-primary min-h-11">
            Continue watching
          </button>
          <button type="button" onClick={onBack} className="btn btn-ghost min-h-11">
            Back to the show
          </button>
        </div>
      </div>
    </div>
  )
}
