# Plex compatibility probe

`plexprobe` reads one library of a Plex Media Server through the adapter the
server's Plex import uses (`internal/plex`) and prints counts and reason codes as
JSON. Use it to check the adapter against a Plex installation you are allowed to
test: it changes nothing on that server, enumerates nothing on the network and
writes no file. It is not part of the server and nothing else runs it.

## Run its tests

From `server/`, with the repository's Go toolchain:

```sh
go test ./internal/plex ./cmd/plexprobe
go test -race ./internal/plex ./cmd/plexprobe
```

All fixtures are authored synthetic examples, not captured personal responses.
Tests use `httptest` and never contact Plex. Authentication tests generate
ephemeral keys in memory; fixture credential strings are deliberately fictional.
They prove the adapter's protocol behavior, not that a real Plex service accepts
the assumptions represented by those fixtures.

## Read a live server

Use an explicitly authorized disposable Plex installation and fictional media.
The probe can also run on an existing installation only with its owner's explicit
authorization. It does not enumerate the network, auto-select a server, change a
watch mark, create an account/profile, refresh metadata or migrate credentials.

Configure these environment variables locally. Keep tokens out of shell history,
command arguments, repository files and captured logs; use a secret manager or a
hidden terminal prompt. Do not paste credentials into a chat or a pull request.

| Variable | Meaning |
| --- | --- |
| `WMM_PLEX_PROBE_URL` | Explicit PMS origin, including port; no query, credentials or path. Prefer HTTPS. Explicit HTTP is accepted for a controlled local test. |
| `WMM_PLEX_PROBE_TOKEN` | Token for the user context being tested on that server. The probe does not obtain or guess it. |
| `WMM_PLEX_PROBE_MACHINE_ID` | Independently known machine identifier of the selected test installation. |
| `WMM_PLEX_PROBE_SECTION_ID` | One visible movie or TV section to read. |
| `WMM_PLEX_PROBE_ACCOUNT_ID` | Optional independently verified **PMS-local** history account ID; omit it to probe current state only. Do not copy a cloud account ID here by assumption. |
| `WMM_PLEX_PROBE_HISTORY_SINCE` | Optional RFC3339 lower bound, requiring `WMM_PLEX_PROBE_ACCOUNT_ID`; normalized down to a whole UTC second. Use a recent window when all retained history exceeds the cap. |
| `WMM_PLEX_PROBE_CLIENT_ID` | Optional identifier associated with an existing grant; otherwise the read probe creates a random identifier. The fresh sign-in mode preserves its generated identifier throughout the run. |

Then, from `server/`:

```sh
go run ./cmd/plexprobe -live
```

Without `-live`, the command shows usage without reading credentials or making
requests. The command has a five-minute overall deadline; each request defaults
to 15 seconds and 4 MiB. A collection is capped at 100 pages of 200 items, at most
20,000 items. Reaching a cap is a failure, never a successful truncated import.
For bounded history, set `WMM_PLEX_PROBE_HISTORY_SINCE` locally (for example
`2026-10-01T00:00:00Z`). Invalid timestamps or a lower bound without a history
account ID fail before any network request, including in fresh-auth mode.
It reads all leaves of the one selected section to observe omitted/zero/positive
state fields, rather than filtering to watched items.

Output is JSON with version, capability statuses, counts and sanitized reasons.
`historyCoverage` records the requested scope: `not_requested`, `all_retained`,
or `since`, with the canonical `historySince` for a bounded read. Coverage alone
is not a success verdict: check `history.status`, including after failures.
It excludes server/user IDs, addresses, titles, paths and credentials. Plain `-live`
labels the user binding `caller_supplied_not_verified_by_probe`: valid returned
history filters do not prove that the caller supplied the correct account mapping.
Do not call a report a successful per-user integration merely because both reads
returned HTTP 200. Empty history cannot prove user identity or lifetime coverage.

Exit 0 means the requested reads completed. Exit 1
means a requested capability failed (current-state counts can still be present if
only history failed). Exit 2 means invalid/missing input or arguments. Keep the
report together with the explicit test scenario and expected counts.

### Fresh owner sign-in and renewal

To test the recommended new-device authentication flow, set the same origin,
machine and section variables, plus `WMM_PLEX_PROBE_CLOUD_ACCOUNT_ID` to the
independently known owner account ID. This is a cloud identity, distinct from the
optional PMS history-account ID. An existing `WMM_PLEX_PROBE_TOKEN` is ignored:

```sh
go run ./cmd/plexprobe -live -auth
```

The command shows a transient Plex authorization URL on the terminal's diagnostic
stream. Open it and complete sign-in on Plex's own page. Do not capture that URL
in logs or a pull request. The command verifies the expected owner, renews the new grant,
verifies the owner again and selects the exact owned server resource. It then runs
an unauthenticated PMS identity preflight and checks the resource credential. An
HTTP 401 response can use the explicit owner-only device bridge described
below; a missing credential never silently substitutes the account token.
The JSON report separates `resourcePmsAccess` and its `httpStatus`,
`ownerDeviceBridge`, `pmsCredentialSource` and `pmsIdentityPreflight` from the final
PMS read result. A successful bridge must not conceal the rejected resource JWT.
History identity supplied to the CLI remains separately caller-bound.

Local in-memory copies of the key, client ID and credentials are retained only
until process exit; no private files are written. This does not end remote
credential validity. Authorizing the test grant can add a device entry to Plex; remove
only that temporary test entry in Plex's Authorized Devices settings when finished,
never the server's PMS device. This CLI mode does not automate Home switching or
invited-user checks, and the history ID given to the CLI is not upgraded to a
verified binding.

## What the adapter relies on

The official authentication flow distinguishes `authToken` from PIN polling and
`auth_token` from refresh. Expired authentication can return HTTP 498. Refresh
uses a nonce and a signed device JWT; polling carries a signed JWT in its query,
so errors cannot include URLs. The official resource-discovery example uses
`clients.plex.tv`. Migrating an existing legacy token to JWT can expire that
token; the adapter deliberately omits migration and rejects existing credentials
on new-device authentication calls. [Official PMS/authentication reference](https://developer.plex.tv/pms/#section/Authenticating-with-Plex)

The official reference specifies JWT in `X-Plex-Token`, but PMS 1.43.5 rejected
the resource JWT with HTTP 401. A maintainer reported the same behavior and an
`/api/v2/devices` bridge on 2026-08-25; its maintained client reads a JSON array
whose device credential field is `token`. That path worked for the server's owner.
[Maintainer report](https://forums.plex.tv/t/question-on-https-clients-plex-tv-api-v2-resources-and-jwt-authentication/934478),
[client implementation](https://github.com/clambin/mediaclients/blob/main/plex/plextv/plextv.go)

`OwnerDeviceCredential` requires prior verification of the owner and exact owned
resource under the same cloud credential. It selects exactly one matching machine
with product `Plex Media Server`; missing/duplicate matches fail. The device
inventory has no ownership field, so it cannot establish ownership on its own.
This is an owner-only bridge, never a fallback for invited or Home-profile reads.
The server's legacy device credential has a lifetime independent of the test
grant. **Do not remove the PMS device to clean up a probe.** Whatever holds these
credentials must revalidate the cloud grant, stop work when authorization is lost,
and purge all locally held derived credentials on disconnect or revocation even if
a PMS token still works.

The shared-user token endpoint and Home switch are separate maintained-client
paths. A shared token is for the chosen server, not proof of cloud-account access.
PlexAPI maps a verified server owner to local history account 1 and other users to
their exact cloud/Home numeric ID. `ResolveHistoryIdentity` applies that maintained
rule only with verified owner/resource/subject inputs and exact `/accounts`
membership; it rejects missing or ambiguous matches and never compares names.
Its evidence label records a rule plus inventory, not independent playback proof.
Comparing a user token's history without an account filter with the
administrator's history filtered to the resolved local ID corroborates a binding;
empty history cannot. [PlexAPI account implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/myplex.html),
[server implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/server.html)

Refreshing the original device key returned the owner identity. Managed-profile
access therefore reacquires a profile credential from the renewed owner grant and
verifies the selected profile again before discovering its server credential.
An unprotected profile supports this sequence; PIN-protected profiles stay
unsupported until their authorization and renewal behavior is verified.

GUID expansion with `includeGuids=1` and treating omitted `viewCount` as zero are
maintained-client conventions. The probe uses the former but keeps the latter as
an unknown state. [Base implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/base.html),
[video implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/video.html)

History is independently available or unavailable. The [Plex dashboard guide](https://support.plex.tv/articles/200871837-status-and-dashboard/)
describes Play History as a Pass feature; test the API capability instead of
promising that detailed history is free. Cloud-synchronized state is not proof of
playback on the selected server, and does not reconstruct all past viewings.
[Watch-state support article](https://support.plex.tv/articles/sync-watch-state-and-ratings/)
