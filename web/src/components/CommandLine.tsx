/** A command to copy and run on the server: one line that scrolls sideways and selects whole. */
export function CommandLine({ children, className = 'mt-1.5' }: { children: string; className?: string }) {
  return (
    <code className={`${className} block overflow-x-auto rounded-md border border-line bg-ground px-3 py-2 font-mono text-[13px] whitespace-nowrap text-neutral-100 select-all`}>
      {children}
    </code>
  )
}
