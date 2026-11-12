import type { components } from '../api/schema'
import { Fact, Panel, StatusPill } from './SettingsPanel'

type CastSettings = components['schemas']['CastSettings']

/**
 * How casting is set up (PLAN-CAST.md), for the server's settings. It comes
 * from config.yaml or the environment (cast.*), so this only shows it, with
 * the addresses a TV would be given for a cast from this browser.
 */
export function CastingSection({ cast }: { cast: CastSettings }) {
  const secure = typeof window !== 'undefined' && window.isSecureContext
  const httpOnly = cast.urls.length > 0 && cast.urls.every((u) => u.startsWith('http://'))
  return (
    <Panel
      title="Casting"
      description={
        <>
          Chromecast and Google TV play as the person who casts. Set these with <code>cast.*</code> in config.yaml or the <code>WATCHMYMEDIA_CAST_*</code> variables.
        </>
      }
      actions={cast.enabled ? <StatusPill tone="ok">On</StatusPill> : <StatusPill tone="off">Off</StatusPill>}
    >
      <dl>
        <Fact label="Receiver app">
          {cast.appId ? <code>{cast.appId}</code> : <span className="text-neutral-400">None set: browsers show no Cast button (the apps carry their own)</span>}
        </Fact>
        <Fact label="Address for TVs">{cast.url ? <code className="break-all">{cast.url}</code> : <span className="text-neutral-400">None set</span>}</Fact>
        <Fact label="TVs are offered">
          {cast.urls.length ? (
            <ul className="space-y-0.5">
              {cast.urls.map((u) => (
                <li key={u}>
                  <code className="break-all">{u}</code>
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-warn">No address a TV can reach</span>
          )}
        </Fact>
        <Fact label="Receiver pages">{cast.receiverOrigins.join(', ') || '—'}</Fact>
      </dl>
      {cast.enabled && httpOnly && (
        <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">
          TVs get plain <code>http://</code> addresses only. If a Chromecast cannot play from them, set <code>cast.url</code> to an HTTPS address it can reach, such as your reverse proxy&rsquo;s
          domain.
        </p>
      )}
      {cast.enabled && !secure && (
        <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">
          Casting from a browser needs this page over HTTPS (Tailscale Serve or your domain); the phone apps cast from any address.
        </p>
      )}
    </Panel>
  )
}
