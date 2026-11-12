export function SegmentedControl<T extends string>({ options, value, onChange }: { options: { value: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="inline-flex rounded-lg border border-line bg-surface p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={`min-h-[36px] rounded-md px-3 text-sm ${value === o.value ? 'bg-surface-2 text-neutral-100 shadow' : 'text-neutral-400 hover:text-neutral-100'}`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Chips<T extends string>({ options, value, onChange }: { options: { value: T; label: string; count?: number }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={`min-h-[36px] rounded-full border px-3 text-sm ${
            value === o.value ? 'border-accent text-neutral-100' : 'border-line-2 text-neutral-400 hover:text-neutral-100'
          }`}
        >
          {o.label}
          {o.count != null && <span className="ml-1 text-neutral-500">{o.count}</span>}
        </button>
      ))}
    </div>
  )
}

export function Select<T extends string>({ label, options, value, onChange }: { label: string; options: { value: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  return (
    <label className="flex items-center gap-2 text-sm text-neutral-400">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as T)}
        className="min-h-[36px] rounded-md border border-line-2 bg-surface px-2 text-sm text-neutral-100"
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

const LETTERS = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ']

export function AZBar({ onJump, available }: { onJump: (letter: string) => void; available: Set<string> }) {
  return (
    <div className="scrollbar-hide flex gap-0.5 overflow-x-auto text-xs">
      {LETTERS.map((l) => (
        <button
          key={l}
          type="button"
          disabled={!available.has(l)}
          onClick={() => onJump(l)}
          className="min-h-[28px] min-w-[22px] rounded px-1 text-neutral-400 hover:text-neutral-100 disabled:opacity-25"
        >
          {l}
        </button>
      ))}
    </div>
  )
}
