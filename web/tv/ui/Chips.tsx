export type ChipOption<V extends string> = { value: V; label: string }

/** A row of choices (sorts, filters, seasons): the chosen one is gold. ◀ ▶ move along it in order. */
export function Chips<V extends string>({ id, options, value, onChange, label }: { id: string; options: readonly ChipOption<V>[]; value: V; onChange(value: V): void; label?: string }) {
  return <div className="tv-chips" role="group" aria-label={label} data-row data-group={id}>
    {options.map((o) => <button key={o.value} type="button" data-focus={`${id}/${o.value}`} className="tv-chip" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>)}
  </div>
}
