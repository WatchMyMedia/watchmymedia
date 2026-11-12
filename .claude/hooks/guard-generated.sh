#!/bin/sh
# PreToolUse hook for Edit, MultiEdit, Write and NotebookEdit: refuses direct
# edits to generated files and names the source and the command to change
# them through instead (AGENTS.md, "Sources of truth and generated files").
# Generators run through Bash, so they are not affected. Exit 2 blocks the
# edit and shows stderr to Claude.
path=$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty')
[ -n "$path" ] || exit 0

refuse() {
  echo "$path is generated. $1" >&2
  exit 2
}

case "$path" in
  */server/internal/db/*.sql.go | */server/internal/db/models.go | */server/internal/db/db.go)
    refuse "Change server/internal/db/queries/*.sql or migrations/, then run make gen-sqlc." ;;
  */api/openapi.json | */api/openapi-3.0.json | */web/src/api/openapi.json | */web/src/api/schema.d.ts)
    refuse "Change the huma types in server/internal/api, then run make gen-api." ;;
  */apple/Kit/Sources/WMMAPI/Generated/*)
    refuse "Run make gen-api, then make apple-api (operations are listed in apple/Kit/Sources/WMMAPI/openapi-generator-config.yaml)." ;;
  */android/core/api/src/main/kotlin/media/watchmy/client/core/api/apis/* | \
  */android/core/api/src/main/kotlin/media/watchmy/client/core/api/models/* | \
  */android/core/api/src/main/kotlin/media/watchmy/client/core/api/infrastructure/*)
    refuse "Run make gen-api, then ./gradlew :core:api:regenerateApi in android/." ;;
  */apple/WatchMyMedia.xcodeproj/* | */apple/App/Info.plist | */apple/TV/Info.plist)
    refuse "Change apple/project.yml, then run make apple-gen." ;;
  */server/internal/webui/dist/*)
    refuse "Change web/, then run make web." ;;
  */brand/export/*)
    refuse "Change brand/src or brand/tools, then run make brand." ;;
esac
exit 0
