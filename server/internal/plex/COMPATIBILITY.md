# Plex compatibility spike (P0)

Part of [PLAN-PLEX-SYNC.md](../../../PLAN-PLEX-SYNC.md#p0-resolve-protocol-uncertainty-before-building-the-feature).
Updated 2026-10-31. The adapter and executable probe have synthetic contract tests
and authorized live evidence from PMS **1.43.5.11079-b3b13e506**. Fresh owner JWT
sign-in and renewal, an owner-device credential bridge, invited-user reads and
unprotected managed Home-profile reads passed. History identity was corroborated
for the owner, one invited user and one managed profile. **The spike is ready for
review within these tested paths; the untested cases below remain explicit gates.**
This is not an enabled sync feature: no server routes, background jobs, database
writes or native-client changes are included.

## Evidence and recommendations

| Area | Implemented and observed | Live evidence still required |
| --- | --- | --- |
| New-device JWT authentication | Fresh Ed25519 PIN/JWK grant and nonce/signature renewal returned the expected owner account. Synthetic tests cover pending/expired PINs and independently verify signatures and claims. | Natural expiry, post-revocation behavior and other accounts/server versions. |
| Owner discovery | Exact owned resource selected under the verified owner grant. Its JWT received PMS HTTP 401; the exact server's legacy device credential from `/api/v2/devices` completed owner reads. Discovery does not automatically contact returned addresses. | Direct PMS JWT acceptance, other versions, and lifetime/revocation of the derived device credential. |
| Shared users | Exact cloud user-ID credential selection; two invited-user movie snapshots used distinct non-owner tokens. One user's own history agreed with the administrator's filtered history. Missing/duplicate tokens fail without owner fallback. | Other invited accounts and restricted-library cases; natural token expiry and share removal. |
| Home users | An unprotected managed profile returned its expected cloud ID and distinct profile/server credentials. State and history reads passed; reacquisition from the renewed owner grant passed in the same session. | PIN-protected profiles, natural expiry, profile removal and unattended operation across process restarts. |
| Identity | Exact `/accounts` inventory plus maintained owner/non-owner rules; owner local ID independently verified from the dashboard. Invited and managed identity bindings corroborated by their own history event IDs. | Other account types, empty-history corroboration and identity changes after restoration. Names are never a crosswalk. |
| Current state | Owner movie/episode leaves, two invited-user movie snapshots and one managed-profile movie snapshot passed. Positive and omitted `viewCount` observed; one watched movie agreed with the UI. | Manual season/show marks, controlled unwatches/rewatches and agent/order differences. Omitted count remains unknown, not a proved unwatch. |
| Retained history | Owner movie traversal and recent TV window passed. Invited and managed samples matched their own account IDs and the administrator's filtered event keys. Old TV rows can lack a current rating key/GUID. | No-Pass entitlement, event stability across restore, exact event semantics and controlled deleted-media cases. |
| Pagination | Live snapshots traversed 16 movie pages and 78 episode pages; movie history traversed 24 pages. Oversized TV history failed explicitly at the 20,000-event cap. | Concurrent changes, other PMS versions and responses lacking `totalSize`. Those valid-but-unhandled responses report `unsupported_paging`. |
| Media identity | Exact TMDB/TVDB/IMDb namespaces; contradictory IDs visible; episode IDs never relabeled as show IDs; legacy episode paths remain unresolved. | Modern/legacy agents, alternate episode order and movie edition mapping. |
| Transport/privacy | Fixed method/path allowlist, no redirects/cookies, bounded responses and deadlines, redacted error reasons and CLI output. | Server-side endpoint approval/DNS policy, durable credential storage and grants are P2 work. This adapter is not exposed to remote callers. |

Recommended direction remains direct PMS reads and explicit user/library mapping.
No compatibility floor is claimed: the official versioned API begins at PMS
1.41.9, but that does not prove this adapter supports every response from that
version onward. The tested version above is evidence for one installation only.

### Authorized compatibility runs — 2026-10-31

The operator first authorized reads through an existing owner session, then
authorized fresh JWT and Home-profile checks. The selected machine identity was
checked without credentials before PMS reads. The owner account reports an active
subscription and Home administration. Cloud account/resource discovery and both
shared-user/Home lists succeeded. Fresh test-device authorization and profile
authorization were exercised; no media, watch state, profile configuration,
metadata, playback or WatchMyMedia database was changed.
Credentials stayed in process memory and child-process environment; no raw
responses, identifiers, titles, addresses or credentials were saved to the repo.

| Scenario | Redacted observation | Conclusion |
| --- | --- | --- |
| Owner movies | 3,011 leaves / 16 pages; 1,338 positive counts, 1,673 omitted counts; 3,004 with recognized external IDs | Full selected-section traversal passed. One existing watched movie matched the Plex UI. |
| Owner episodes | 15,498 leaves / 78 pages; 10,275 positive counts, 5,223 omitted counts; 15,347 with recognized external IDs | Full selected-section traversal passed. Episode and movie IDs remain distinct. |
| Invited user A movies | Exact cloud-ID credential selection; credential differs from owner; 3,011 leaves / 16 pages; 125 positive counts | Selected server token returns different state from the owner. |
| Invited user B movies | Exact cloud-ID credential selection; credential differs from owner; 3,011 leaves / 16 pages; no positive counts | No positive watched marks were returned, with no fallback to owner state. Omitted counts remain unknown. |
| Fresh owner grant and renewal | Ed25519 PIN/JWK authorization succeeded; signed PIN exchange and nonce refresh each returned a JWT whose cloud account matched the expected owner | Fresh cloud authentication and immediate renewal passed. Natural expiry and revocation behavior were not tested. |
| Resource JWT and owner-device bridge | Exact owned resource's JWT received HTTP 401 from PMS libraries. The exact matching PMS device's legacy credential completed 3,011 movie leaves / 16 pages with 1,338 positive counts | The tested owner path is cloud JWT plus an explicit owner-device credential bridge. Direct PMS JWT acceptance failed. |
| PMS account inventory | 36 canonical entries, including account 0; owner cloud ID absent from inventory, local owner ID 1 present and independently verified | Retain the zero placeholder in inventory counts but never bind it. Keep cloud and PMS IDs separate. |
| Managed Home profile | Expected cloud ID verified; distinct profile/server credentials; 3,011 movie leaves / 16 pages, 10 positive counts | The selected unprotected managed profile had its own state; no owner-state fallback occurred. |
| Managed-profile history | Maintained-rule inventory binding; unfiltered own-history and administrator-filtered samples agreed on the local account ID and five event keys | The sampled profile-to-history binding was corroborated without names or watch-state changes. |
| Managed-profile reacquisition after owner renewal | The original key renewed the owner grant, not the profile grant. Repeating profile authorization from that renewed owner returned the same profile identity, 10 positive counts and five matching history keys | Renew owner, then reacquire the unprotected profile and verify it again. This proves the in-session sequence, not persistence, natural expiry or protected-profile renewal. |
| Invited-user history | Exact cloud-ID credential selection; distinct server credential; 3,011 movie leaves / 16 pages, 125 positive counts; own and administrator-filtered history agreed on account IDs and two event keys | The sampled invited-user history binding was corroborated. The second invited user's history binding was not corroborated. |
| Owner movie history | 4,633 retained events / 24 pages; account and section validated on every row | Retained-history traversal passed. The dashboard owner's history link independently established the PMS-local ID. |
| Owner TV history, all retained | Endpoint reported 29,156 events; probe returned `limit_exceeded` | Full history was not read and is not reported as complete. |
| Owner TV history, since 2026-10-01 UTC | 163 events / 1 page; account, section and lower bound validated | Bounded history works. The report explicitly records the requested coverage. |
| Old TV history sample | First three rows have history key, account, section and date, but no current rating key or GUID | History rows can lack identifiers needed for a confident current-item match. Preserve unresolved events; do not fabricate current-item matches from titles. Deletion cause was not established. |
| Episode parent metadata sample | Exact grandparent key resolves a show with TMDB/TVDB/IMDb IDs; no ordering field returned | Parent lookup supplies show identity, but default metadata does not prove episode ordering. |

The two invited snapshots each exposed seven contradictory external-ID sets;
recognized IDs are evidence to validate, not automatic match permission. No movie
edition field was observed. These are observations of existing data, not controlled
edition, deleted-media or unwatch tests. Invited-user snapshots were not independently
compared with each user's UI. History was corroborated for one invited user and one
managed profile; this does not prove every mapping on the server. All 14 non-owner
Home profiles advertised themselves as unprotected managed profiles, so no
PIN-protected profile was available for this run.

Both temporary test-device grants were removed through Plex Authorized Devices,
and their entries were verified absent. No post-removal credential requests were
used to test revocation. Cleanup does not establish that derived credentials were
revoked, and the server's PMS device was left intact.

The large TV history motivated `WMM_PLEX_PROBE_HISTORY_SINCE`. It preserves the
existing caps and distinguishes a completed date window from all retained history.
P3 still needs resumable, bounded backfill rather than simply raising this spike's
in-memory limits. These checks do not establish lifetime history, no-Pass access,
natural-expiry/revocation behavior or a minimum supported PMS version.

### Protocol details that affect implementation

The official authentication flow distinguishes `authToken` from PIN polling and
`auth_token` from refresh. Expired authentication can return HTTP 498. Refresh
uses a nonce and a signed device JWT; polling carries a signed JWT in its query,
so errors cannot include URLs. The official resource-discovery example uses
`clients.plex.tv`. Migrating an existing legacy token to JWT can expire that
token; the adapter deliberately omits migration and rejects existing credentials
on new-device authentication calls. [Official PMS/authentication reference](https://developer.plex.tv/pms/#section/Authenticating-with-Plex)

The official reference specifies JWT in `X-Plex-Token`, but the tested PMS rejected
the resource JWT with HTTP 401. A maintainer reported the same behavior and an
`/api/v2/devices` bridge on 2026-08-25; its maintained client reads a JSON array
whose device credential field is `token`. This path passed the live owner check.
[Maintainer report](https://forums.plex.tv/t/question-on-https-clients-plex-tv-api-v2-resources-and-jwt-authentication/934478),
[client implementation](https://github.com/clambin/mediaclients/blob/main/plex/plextv/plextv.go)

`OwnerDeviceCredential` requires prior verification of the owner and exact owned
resource under the same cloud credential. It selects exactly one matching machine
with product `Plex Media Server`; missing/duplicate matches fail. The device
inventory has no ownership field, so it cannot establish ownership on its own.
This is an owner-only bridge, never a fallback for invited or Home-profile reads.
The server's legacy device credential has a lifetime independent of the test
grant. **Do not remove the PMS device to clean up a probe.** P2 must revalidate the
cloud grant, stop work when authorization is lost, and purge all locally held
derived credentials on disconnect or revocation even if a PMS token still works.

The shared-user token endpoint and Home switch are separate maintained-client
paths. A shared token is for the chosen server, not proof of cloud-account access.
PlexAPI maps a verified server owner to local history account 1 and other users to
their exact cloud/Home numeric ID. `ResolveHistoryIdentity` applies that maintained
rule only with verified owner/resource/subject inputs and exact `/accounts`
membership; it rejects missing or ambiguous matches and never compares names.
Its evidence label records a rule plus inventory, not independent playback proof.
The live non-owner checks additionally compared user-token history without an
account filter with administrator history filtered to the resolved local ID.
Empty history cannot provide that corroboration. [PlexAPI account implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/myplex.html),
[server implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/server.html)

Refreshing the original device key returned the owner identity. Managed-profile
access therefore reacquires a profile credential from the renewed owner grant and
verifies the selected profile again before discovering its server credential.
The tested unprotected profile supported this sequence; protected profiles must
remain unsupported until their authorization and renewal behavior is verified.

GUID expansion with `includeGuids=1` and treating omitted `viewCount` as zero are
maintained-client conventions. The probe uses the former but retains an unknown
state for the latter until live evidence justifies a rule. [Base implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/base.html),
[video implementation](https://python-plexapi.readthedocs.io/en/latest/_modules/plexapi/video.html)

History is independently available or unavailable. The [Plex dashboard guide](https://support.plex.tv/articles/200871837-status-and-dashboard/)
describes Play History as a Pass feature; test the API capability instead of
promising that detailed history is free. Cloud-synchronized state is not proof of
playback on the selected server, and does not reconstruct all past viewings.
[Watch-state support article](https://support.plex.tv/articles/sync-watch-state-and-ratings/)

## Run the offline spike

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

## Opt-in live read probe

Use an explicitly authorized disposable Plex installation and fictional media.
The probe can also run on an existing installation only with its owner's explicit
authorization. It does not enumerate the network, auto-select a server, change a
watch mark, create an account/profile, refresh metadata or migrate credentials.

Configure these environment variables locally. Keep tokens out of shell history,
command arguments, repository files and captured logs; use a secret manager or a
hidden terminal prompt. Do not paste credentials into a chat or PR.

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
state fields, rather than filtering to watched items in this diagnostic phase.

Output is JSON with version, capability statuses, counts and sanitized reasons.
`historyCoverage` records the requested scope: `not_requested`, `all_retained`,
or `since`, with the canonical `historySince` for a bounded read. Coverage alone
is not a success verdict: check `history.status`, including after failures.
It excludes server/user IDs, addresses, titles, paths and credentials. Plain `-live`
labels the user binding `caller_supplied_not_verified_by_probe`: valid returned
history filters do not prove that the caller supplied the correct account mapping.
Do not call a report a successful per-user integration merely because both reads
returned HTTP 200. Empty history cannot prove user identity or lifetime coverage.

Exit 0 means requested reads completed, not that P0's entire matrix passed. Exit 1
means a requested capability failed (current-state counts can still be present if
only history failed). Exit 2 means invalid/missing input or arguments. Keep the
report together with the explicit test scenario and expected counts.

### Optional fresh owner sign-in and renewal

To test the recommended new-device authentication flow, set the same origin,
machine and section variables, plus `WMM_PLEX_PROBE_CLOUD_ACCOUNT_ID` to the
independently known owner account ID. This is a cloud identity, distinct from the
optional PMS history-account ID. An existing `WMM_PLEX_PROBE_TOKEN` is ignored:

```sh
go run ./cmd/plexprobe -live -auth
```

The command shows a transient Plex authorization URL on the terminal's diagnostic
stream. Open it and complete sign-in on Plex's own page. Do not capture that URL
in logs or the PR. The command verifies the expected owner, renews the new grant,
verifies the owner again and selects the exact owned server resource. It then runs
an unauthenticated PMS identity preflight and checks the resource credential. An
HTTP 401 response can use the explicit owner-only device bridge described
above; a missing credential never silently substitutes the account token.
The JSON report separates `resourcePmsAccess` and its `httpStatus`,
`ownerDeviceBridge`, `pmsCredentialSource` and `pmsIdentityPreflight` from the final
PMS read result. A successful bridge must not conceal the rejected resource JWT.
History identity supplied to the CLI remains separately caller-bound.

Local in-memory copies of the key, client ID and credentials are retained only
until process exit; no private files are written. This does not end remote
credential validity. Authorizing the test grant can add a device entry to Plex; remove
only that temporary test entry in Plex's Authorized Devices settings when finished,
never the server's PMS device. This CLI mode does not automate Home switching or
invited-user checks. Those adapter paths were exercised separately in the live
run above; the CLI's supplied history ID is not upgraded to a verified binding.

## Live acceptance worksheet

The authorized observations above cover part of this worksheet. Unexercised cases
remain **not run**. The test operator records PMS version, account kind,
Pass capability, library agent, explicit scenario, expected result and redacted
probe result. No personal names, media, network addresses, tokens or raw payloads
belong in this repository. Do not automate watch-state mutations on a personal
server; use manual preparation on the disposable fixture installation.

1. Owner and shared users watch different synthetic movies/episodes. Prove each
   token sees the intended current state, including an intentionally empty user.
   Verify the cloud/Home/PMS identity mapping with independent evidence.
2. Repeat for managed and PIN-protected profiles. Verify failed PIN, share removal,
   profile removal, token expiry, renewal and revoked credentials. No owner fallback.
3. Create an isolated new PIN/JWK grant, verify resulting account identity, discover
   the selected owned resource, compare intended JWT and resource-token paths, and
   renew the grant. Never migrate an existing legacy credential during the spike.
4. Manually mark/unmark movie, episode, season and show; start a partial rewatch.
   Compare leaf fields to expected current state. Record omission behavior.
5. Compare retained history with/without Pass, same-second events, old/deleted
   media and a deliberately cleared history. A prior viewing with a current
   unwatch must remain a historical event only.
6. Test modern and legacy agents, conflicting IDs, specials, editions and alternate
   episode order. Confirm which parent metadata can resolve exact show identity;
   leave ambiguous mappings unresolved.
7. Traverse multiple pages while adding/removing an item. Record response paging
   fields; ensure incomplete/drifting responses cannot appear as a complete read.
8. Restore/recreate the disposable Plex database with the same machine ID and
   reused item/history keys. Establish collision/rebaseline requirements; a
   machine identifier alone is not a dataset generation.

The bounded P0 result is reviewable for the tested owner, invited-user and
unprotected managed-profile paths. It is not a universal compatibility claim or
permission to enable imports. P2 must keep unverified account types and credential
lifecycle cases gated; it must not promise PIN-protected-profile automation,
a minimum PMS version, universal history entitlement or complete history.

## Validation ledger

- Final offline protocol/CLI tests: 54 top-level tests plus 167 subtests passed.
  `go test -race ./internal/plex ./cmd/plexprobe` passed on the final code.
- Final `go vet ./...` and full `go test -p 1 ./...` passed (unchanged packages
  used Go's test cache).
  An earlier parallel full-suite run hit existing login-admission/history-speed
  time budgets under host load; both tests passed in isolated fresh runs.
- Linux AMD64 and Windows AMD64 `go vet ./...`; Linux ARM64 `go build ./...`: passed.
- `make lint`: passed (Go lint: zero issues; web lint also passed).
- Earlier web production build and final `make agents-check`: passed. The guidance checker
  expects the ignored `web/dist*` output, so the build precedes it in a fresh worktree.
- Independent protocol review: addressed value-formatting secret redaction and
  container-level library identity, with regression tests. Final owner-device,
  history-identity, CLI and documentation reviews found no remaining material
  issues. Local documentation links and `git diff --check` passed.
- Live fresh owner JWT/renewal, owner-device bridge, owner movie/TV state,
  invited-user state, unprotected managed-profile access/reacquisition, and the
  sampled history bindings passed on the version above. Direct resource-JWT PMS
  access failed with HTTP 401; all-retained TV history failed explicitly at the cap.
- PIN-protected profiles, natural expiry/post-revocation behavior, Pass differences,
  controlled state mutations, concurrent paging and restoration: **not run**.
