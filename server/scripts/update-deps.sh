#!/usr/bin/env bash
# Keeps the server's Go modules and Go version up to date.
#
# With no argument it only reports, and changes nothing: the modules in
# server/go.mod that have newer releases, retracted or deprecated versions,
# newer major versions (which change import paths, so are never taken
# automatically), the newest Go patch release, the sqlc and golangci-lint
# versions CI pins against their latest, and govulncheck's findings.
#
# patch takes the patch releases of every module; minor takes minor and
# patch releases (go get -u). Both also raise the go line in go.mod to the
# newest patch of the same Go release: CI and the release builds use that
# version (setup-go reads go-version-file), so the shipped binaries only get
# the standard library's security fixes once it moves. Then it tidies and
# verifies the modules, runs the checks CI runs (build, vet for macOS, Linux
# and Windows, golangci-lint, the tests) and govulncheck, and says whether
# the OpenAPI documents changed, as a huma update can change them.
#
#   server/scripts/update-deps.sh          report only
#   server/scripts/update-deps.sh patch    patch releases and the Go patch
#   server/scripts/update-deps.sh minor    minor and patch releases and the Go patch
#   KEEP_GO=1 …                            leave the go line as it is
#   SKIP_TESTS=1 …                         skip go test (build, vet and lint still run)
set -euo pipefail

# govulncheck runs at a pinned version; the report says when a newer one exists.
GOVULNCHECK=v1.9.0

mode=${1:-report}
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root/server"

die() { echo "update-deps: $*" >&2; exit 1; }
say() { printf '\n== %s\n' "$*"; }

case $mode in
  report | patch | minor) ;;
  -h | --help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
  *) die "unknown mode $mode (expected patch or minor, or nothing to only report)" ;;
esac
command -v go >/dev/null || die "go is not on PATH"

# requires prints "path version direct|indirect" for each require in a go.mod.
requires() {
  awk '
    /^require \(/ { block = 1; next }
    block && /^\)/ { block = 0 }
    block && NF >= 2 { print $1, $2, ($4 == "indirect" ? "indirect" : "direct") }
    /^require [^(]/ { print $2, $3, ($5 == "indirect" ? "indirect" : "direct") }
  ' "${1:-go.mod}"
}

# latest prints the newest version of a module, or nothing when it has none.
latest() { go list -m -f '{{.Version}}' "$1@latest" 2>/dev/null || true; }

# go_versions sets go_have (go.mod's go line), go_line (its release, as in
# 1.27), go_patch (that release's newest patch) and go_next (a newer release).
go_versions() {
  go_have=$(awk '$1 == "go" { print $2 }' go.mod)
  go_line=$(cut -d. -f1-2 <<<"$go_have")
  go_patch= go_next=
  local releases
  if ! releases=$(curl -fsSL --max-time 20 'https://go.dev/dl/?mode=json' | grep -o '"version": *"go[0-9.]*"' | grep -o '[0-9][0-9.]*'); then
    echo "  (could not read the Go releases from go.dev)"
    return
  fi
  go_patch=$(grep -x "${go_line//./\\.}\(\.[0-9]*\)\{0,1\}" <<<"$releases" | head -1 || true)
  go_next=$(head -1 <<<"$releases")
  [[ $go_next == "$go_line" || $go_next == "$go_line".* ]] && go_next=
  return 0
}

report_modules() {
  say "Modules with newer releases"
  local paths
  paths=$(requires | awk '{ print $1 }')
  # shellcheck disable=SC2086 # one argument per module path
  go list -m -u -f '{{.Path}} {{.Version}} {{with .Update}}{{.Version}}{{else}}-{{end}} {{if .Indirect}}indirect{{else}}direct{{end}} {{with .Retracted}}retracted{{else}}-{{end}} {{.Deprecated}}' $paths |
    sort -k4,4 -k1,1 | awk '
      $3 != "-" { printf "  %-44s %-14s -> %s%s\n", $1, $2, $3, ($4 == "indirect" ? "  (indirect)" : ""); n++ }
      $5 == "retracted" { warn = warn sprintf("  %s %s is retracted by its authors\n", $1, $2) }
      NF > 5 { d = $0; sub(/^([^ ]+ ){5}/, "", d); warn = warn sprintf("  %s is deprecated: %s\n", $1, d) }
      END { if (!n) print "  none: every module is at its newest release"; if (warn) printf "\n%s", warn }'

  say "Newer major versions (change the import path, so update them by hand)"
  local found= path version next v
  while read -r path version _; do
    [[ $path == gopkg.in/* ]] && continue
    if [[ $path =~ ^(.*)/v([0-9]+)$ ]]; then
      next="${BASH_REMATCH[1]}/v$((BASH_REMATCH[2] + 1))"
    else
      next="$path/v2"
    fi
    v=$(latest "$next")
    if [[ -n $v ]]; then
      printf '  %-44s %-14s -> %s %s\n' "$path" "$version" "$next" "$v"
      found=1
    fi
  done < <(requires | awk '$3 == "direct"')
  [[ -n $found ]] || echo "  none"
}

report_tools() {
  say "Go"
  go_versions
  if [[ -n $go_patch && $go_patch != "$go_have" ]]; then
    echo "  go.mod says go $go_have; go $go_patch is out (patch and minor take it)"
  elif [[ -n $go_patch ]]; then
    echo "  go.mod says go $go_have, the newest $go_line release"
  fi
  [[ -z $go_next ]] || echo "  go $go_next is out: moving to it is a change of its own (go.mod, deploy/Dockerfile, AGENTS.md)"

  say "Tools CI pins"
  local ci=../.github/workflows/ci.yml have new
  have=$(grep -o 'sqlc/cmd/sqlc@v[0-9.]*' "$ci" | head -1 | cut -d@ -f2)
  new=$(latest github.com/sqlc-dev/sqlc)
  tool_line sqlc "$have" "$new" "the pins in .github/workflows/ci.yml and server/AGENTS.md, then make gen-sqlc"
  have=$(grep -A3 'golangci-lint-action' "$ci" | sed -n 's/.*version: *\(v[0-9.]*\).*/\1/p' | head -1)
  new=$(latest github.com/golangci/golangci-lint/v2)
  tool_line golangci-lint "$have" "$new" "the pins in .github/workflows/ci.yml and server/AGENTS.md"
  new=$(latest golang.org/x/vuln)
  tool_line govulncheck "$GOVULNCHECK" "$new" "GOVULNCHECK in server/scripts/update-deps.sh"
}

tool_line() {
  if [[ -n $3 && $3 != "$2" ]]; then
    printf '  %-14s %-10s -> %s: update %s\n' "$1" "$2" "$3" "$4"
  else
    printf '  %-14s %s, the newest\n' "$1" "$2"
  fi
}

vulncheck() {
  say "govulncheck"
  if go run "golang.org/x/vuln/cmd/govulncheck@$GOVULNCHECK" ./...; then
    return 0
  fi
  vulnerable=1
  return 0
}

vulnerable=
if [[ $mode == report ]]; then
  report_modules
  report_tools
  vulncheck
  echo
  echo "Nothing was changed. Run with patch or minor to update."
  exit 0
fi

git diff --quiet -- go.mod go.sum || die "go.mod or go.sum has changes that are not committed; commit or set them aside first"
before=$(mktemp)
trap 'rm -f "$before"' EXIT
cp go.mod "$before"

say "Updating modules ($mode)"
if [[ $mode == patch ]]; then
  go get -t -u=patch ./...
else
  go get -t -u ./...
fi

if [[ ${KEEP_GO:-} != 1 ]]; then
  go_versions
  if [[ -n $go_patch && $go_patch != "$go_have" ]]; then
    if [[ $(go env GOTOOLCHAIN) == local* && $(go env GOVERSION) != "go$go_patch" ]]; then
      echo "  GOTOOLCHAIN is local and this is $(go env GOVERSION), so the go line stays at $go_have; install go $go_patch and run again"
    else
      echo "  go $go_have -> $go_patch"
      go get "go@$go_patch" toolchain@none
    fi
  fi
fi
go mod tidy
go mod verify

say "Changes to go.mod"
changes=$(LC_ALL=C join -a 1 -a 2 -e - -o 0,1.2,2.2 <(requires "$before" | LC_ALL=C sort) <(requires | LC_ALL=C sort) |
  awk '$2 != $3 { printf "  %-44s %-14s -> %s\n", $1, ($2 == "-" ? "(new)" : $2), ($3 == "-" ? "(removed)" : $3) }')
go_now=$(awk '$1 == "go" { print $2 }' go.mod)
go_was=$(awk '$1 == "go" { print $2 }' "$before")
[[ $go_now == "$go_was" ]] || changes=$(printf '  %-44s %-14s -> %s\n%s' go "$go_was" "$go_now" "$changes")
if [[ -z $changes ]]; then
  echo "  none: everything was already up to date"
  exit 0
fi
echo "$changes"

say "Build and vet (macOS, Linux, Windows)"
go build ./...
go vet ./...
GOOS=linux GOARCH=amd64 go vet ./...
GOOS=windows GOARCH=amd64 go vet ./...
if command -v golangci-lint >/dev/null; then
  say "golangci-lint"
  golangci-lint run ./...
fi
if [[ ${SKIP_TESTS:-} != 1 ]]; then
  say "Tests"
  go test -count=1 ./...
fi
vulncheck

say "API documents"
api_changed=
go run ./cmd/server openapi | cmp -s - ../api/openapi.json || api_changed=1
go run ./cmd/server openapi -3.0 | cmp -s - ../api/openapi-3.0.json || api_changed=1
if [[ -n $api_changed ]]; then
  echo "  The OpenAPI documents changed: run make gen-api, make apple-api and make apple-test from the root, and check the diff before committing."
else
  echo "  unchanged"
fi

echo
echo "Done. Review git diff -- server/go.mod server/go.sum and commit both."
[[ -z $vulnerable ]] || die "govulncheck still reports vulnerabilities in code the server calls (above)"
