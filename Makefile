SHELL := /bin/bash
export PATH := /opt/homebrew/bin:$(PATH)

WEB_DIST := server/internal/webui/dist
VERSION  ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
# WatchMyMedia's OpenSubtitles API key, from a CI secret on release builds;
# builds without it have no online subtitles.
OPENSUBTITLES_API_KEY ?=
LDFLAGS  = -s -w -X main.version=$(VERSION) -X main.openSubtitlesKey=$(OPENSUBTITLES_API_KEY)

.PHONY: help dev web-dev gen gen-sqlc gen-api web build build-universal release ffmpeg-macos ffmpeg-windows windows-app windows-installer windows-test macos-app macos-dmg test bench lint docker docker-up clean fixture fixture-serve apple-api apple-gen apple-test apple-build apple-test-sim apple-archive apple-upload apple-tv-archive apple-tv-upload apple-e2e apple-tv-build apple-tv-test-sim agents-check server-deps android-verify-metadata
.PHONY: streaming-check release-tag

PLATFORMS := darwin/arm64 darwin/amd64 linux/amd64 linux/arm64 windows/amd64

help: ## Show targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-12s %s\n", $$1, $$2}'

dev: ## Run the Go server with hot reload (data dir: ./data)
	cd server && air

web-dev: ## Run the Vite dev server (proxies /api to :27587)
	cd web && pnpm dev

gen: gen-sqlc gen-api ## Regenerate sqlc code and the web API types

gen-sqlc: ## Regenerate Go query code from internal/db/queries
	cd server && sqlc generate

gen-api: ## Dump OpenAPI (web types, and api/ for the native apps) and regenerate web/src/api/schema.d.ts
	mkdir -p api
	cd server && go run ./cmd/server openapi > ../api/openapi.json
	cd server && go run ./cmd/server openapi -3.0 > ../api/openapi-3.0.json
	cp api/openapi.json web/src/api/openapi.json
	cd web && pnpm gen:api

# The iPhone app (apple/, PLAN-IOS.md). SIM names the simulator xcodebuild uses;
# without one by that name, apple/scripts/simulator.sh picks the newest iPhone Pro.
SIM ?= iPhone 17 Pro
# The Apple TV app (apple/TV, PLAN-IOS.md §11.2), on TV_SIM or the newest Apple TV 4K.
TV_SIM ?= Apple TV 4K (4th generation)
# Optional CI override for verbose Xcode diagnostics; empty keeps local defaults.
APPLE_TV_TEST_DIAGNOSTICS ?=
APPLE_DERIVED := apple/build/DerivedData
APPLE_GEN := apple/Tools/.build/release/swift-openapi-generator
# Simulator builds are signed to run locally (ad hoc, no account or profile):
# unsigned, the Keychain refuses the app (errSecMissingEntitlement), so the
# sign-in would not survive a relaunch.
APPLE_SIM_SIGNING := CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=

apple-api: ## Regenerate the Swift API client in apple/Kit/Sources/WMMAPI from api/openapi.json (after gen-api)
	cd apple/Tools && swift build --product swift-openapi-generator -c release
	mkdir -p apple/build && python3 -I apple/scripts/openapi-for-swift.py api/openapi.json apple/build/openapi-swift.json
	rm -rf apple/Kit/Sources/WMMAPI/Generated
	$(APPLE_GEN) generate apple/build/openapi-swift.json --config apple/Kit/Sources/WMMAPI/openapi-generator-config.yaml --output-directory apple/Kit/Sources/WMMAPI/Generated
	python3 -I apple/scripts/open-response-enums.py api/openapi.json apple/Kit/Sources/WMMAPI/Generated

# VERSION, only when given here (its default, git describe, is no release),
# writes that release's version into the project for archiving in Xcode.
apple-gen: ## Generate apple/WatchMyMedia.xcodeproj from apple/project.yml (needs xcodegen; VERSION=vX.Y.Z sets its version)
	$(if $(filter command line,$(origin VERSION)),apple/scripts/generate.sh $(VERSION),cd apple && xcodegen generate --quiet)

apple-test: ## Run the Kit's unit tests on this Mac (no simulator)
	cd apple/Kit && swift test

apple-build: apple-gen ## Build the iPhone app for the simulator -> apple/build/DerivedData
	xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme WatchMyMedia -destination 'generic/platform=iOS Simulator' \
	  -derivedDataPath $(APPLE_DERIVED) $(APPLE_SIM_SIGNING) build

apple-test-sim: apple-gen ## Build the app and run every test on the $(SIM) simulator
	id=$$(apple/scripts/simulator.sh "$(SIM)") && \
	rm -rf apple/build/Tests.xcresult && \
	xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme WatchMyMedia -destination "platform=iOS Simulator,id=$$id" \
	  -derivedDataPath $(APPLE_DERIVED) -resultBundlePath apple/build/Tests.xcresult $(APPLE_SIM_SIGNING) test

apple-tv-build: apple-gen ## Build the Apple TV app for the simulator -> apple/build/DerivedData
	xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme 'WatchMyMedia TV' -destination 'generic/platform=tvOS Simulator' \
	  -derivedDataPath $(APPLE_DERIVED) $(APPLE_SIM_SIGNING) build

apple-tv-test-sim: apple-gen ## Build the Apple TV app and run every test on the $(TV_SIM) simulator
	id=$$(apple/scripts/simulator.sh "$(TV_SIM)" appletv) && \
	rm -rf apple/build/TVTests.xcresult && \
	xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme 'WatchMyMedia TV' -destination "platform=tvOS Simulator,id=$$id" \
	  -derivedDataPath $(APPLE_DERIVED) -resultBundlePath apple/build/TVTests.xcresult $(APPLE_SIM_SIGNING) \
	  $(if $(APPLE_TV_TEST_DIAGNOSTICS),-collect-test-diagnostics $(APPLE_TV_TEST_DIAGNOSTICS)) test

# apple-e2e runs the app on $(SIM) against a server: E2E_SERVER as typed in
# the server picker (from the simulator, localhost is this Mac), and an account
# on it. It starts from a fresh install, so the picker shows first.
E2E_SERVER ?= localhost:27588
E2E_USER ?= viewer
E2E_PASSWORD ?= fixture-viewer-1

apple-e2e: apple-gen ## Run the app end to end on $(SIM) against E2E_SERVER (default: the fixture server) -> apple/build/E2E.xcresult
	id=$$(apple/scripts/simulator.sh "$(SIM)") || exit 1; \
	xcrun simctl boot "$$id" 2>/dev/null; xcrun simctl uninstall "$$id" media.watchmy.client 2>/dev/null; xcrun simctl keychain "$$id" reset; \
	rm -rf apple/build/E2E.xcresult && \
	TEST_RUNNER_WMM_E2E_SERVER="$(E2E_SERVER)" TEST_RUNNER_WMM_E2E_USER="$(E2E_USER)" TEST_RUNNER_WMM_E2E_PASSWORD="$(E2E_PASSWORD)" \
	xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme WatchMyMediaE2E -destination "platform=iOS Simulator,id=$$id" \
	  -derivedDataPath $(APPLE_DERIVED) -resultBundlePath apple/build/E2E.xcresult $(APPLE_SIM_SIGNING) test

# Signing for apple-archive and apple-upload: an App Store Connect API key
# (README, "Releasing the iPhone app"). Without ASC_KEY_PATH, Xcode uses the
# accounts signed in on this Mac.
APPLE_AUTH = $(if $(ASC_KEY_PATH),-authenticationKeyPath $(ASC_KEY_PATH) -authenticationKeyID $(ASC_KEY_ID) -authenticationKeyIssuerID $(ASC_ISSUER_ID))

apple-archive: apple-gen ## Archive the iPhone app for the App Store (VERSION=vX.Y.Z) -> apple/build/WatchMyMedia.xcarchive
	versions=$$(apple/scripts/version.sh $(VERSION)) && \
	xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme WatchMyMedia -configuration Release -destination 'generic/platform=iOS' \
	  -archivePath apple/build/WatchMyMedia.xcarchive -allowProvisioningUpdates $(APPLE_AUTH) $$versions archive

apple-upload: apple-archive ## Archive and upload the iPhone app to TestFlight (VERSION=vX.Y.Z)
	xcodebuild -exportArchive -archivePath apple/build/WatchMyMedia.xcarchive -exportOptionsPlist apple/ExportOptions.plist \
	  -exportPath apple/build/export -allowProvisioningUpdates $(APPLE_AUTH)

# The Apple TV app is archived unsigned and signed when exported. With no Apple
# TV registered to the team, Xcode can't make the tvOS development profile its
# archive step signs with ("Your team has no devices"); the export signs with
# Apple Distribution and an App Store profile, which needs no device.
apple-tv-archive: apple-gen ## Archive the Apple TV app for the App Store, unsigned (VERSION=vX.Y.Z) -> apple/build/WatchMyMediaTV.xcarchive
	versions=$$(apple/scripts/version.sh $(VERSION)) && \
	xcodebuild -project apple/WatchMyMedia.xcodeproj -scheme 'WatchMyMedia TV' -configuration Release -destination 'generic/platform=tvOS' \
	  -archivePath apple/build/WatchMyMediaTV.xcarchive CODE_SIGNING_ALLOWED=NO $$versions archive

apple-tv-upload: apple-tv-archive ## Sign the Apple TV archive for the App Store and upload it to TestFlight (VERSION=vX.Y.Z)
	xcodebuild -exportArchive -archivePath apple/build/WatchMyMediaTV.xcarchive -exportOptionsPlist apple/ExportOptions.plist \
	  -exportPath apple/build/export-tv -allowProvisioningUpdates $(APPLE_AUTH)

web: ## Build the web app and copy it into the server for embedding
	cd web && pnpm install --frozen-lockfile && pnpm build
	rm -rf $(WEB_DIST)/*
	cp -R web/dist/. $(WEB_DIST)/

build: web ## Build a native binary with the web UI embedded -> bin/watchmymedia
	cd server && CGO_ENABLED=0 go build -trimpath -ldflags="$(LDFLAGS)" -o ../bin/watchmymedia ./cmd/server

ffmpeg-macos: ## Download the pinned ffmpeg build for macOS into build/ffmpeg/darwin-{arm64,amd64}
	deploy/macos/fetch-ffmpeg.sh arm64
	deploy/macos/fetch-ffmpeg.sh amd64

ffmpeg-windows: ## Download the pinned ffmpeg build for Windows into build/ffmpeg/windows-amd64
	deploy/macos/fetch-ffmpeg.sh windows-amd64

windows-app: web ffmpeg-windows ## Build the Windows x64 tray app with the server and FFmpeg -> build/windows-app
	VERSION=$(VERSION) windows/build.sh

windows-installer: windows-app ## Build the development Windows x64 NSIS installer -> build/windows-installer
	VERSION=$(VERSION) windows/installer.sh

windows-test: ## Test the Windows app supervisor on this host and vet the Windows code
	cd windows && go test -race ./... && GOOS=windows GOARCH=amd64 go vet ./...

macos-app: build ## Build the menu bar app for this Mac -> build/WatchMyMedia.app
	deploy/macos/fetch-ffmpeg.sh $(shell uname -m | sed 's/x86_64/amd64/')
	VERSION=$(VERSION) macos/build-app.sh

build-universal: web ## Build a universal (arm64 + x86_64) macOS binary -> bin/watchmymedia
	mkdir -p bin build
	@for arch in arm64 amd64; do \
	  echo "building build/watchmymedia-$$arch"; \
	  (cd server && CGO_ENABLED=0 GOOS=darwin GOARCH=$$arch go build -trimpath -ldflags="$(LDFLAGS)" -o ../build/watchmymedia-$$arch ./cmd/server) || exit 1; \
	done
	lipo -create build/watchmymedia-arm64 build/watchmymedia-amd64 -output bin/watchmymedia

macos-dmg: build-universal ffmpeg-macos ## Universal app, signed and notarised when configured -> dist/WatchMyMedia-<version>.dmg
	UNIVERSAL=1 VERSION=$(VERSION) macos/build-app.sh
	VERSION=$(VERSION) macos/make-dmg.sh

release: web ffmpeg-macos ffmpeg-windows ## Cross-compile binaries for every platform into dist/ (macOS tarballs and the Windows zip include ffmpeg)
	rm -rf dist && mkdir -p dist
	@for p in $(PLATFORMS); do \
	  os=$${p%/*}; arch=$${p#*/}; ext=""; [ "$$os" = windows ] && ext=".exe"; \
	  out=dist/watchmymedia-$(VERSION)-$$os-$$arch; \
	  echo "building $$out$$ext"; \
	  (cd server && CGO_ENABLED=0 GOOS=$$os GOARCH=$$arch go build -trimpath -ldflags="$(LDFLAGS)" -o ../$$out$$ext ./cmd/server) || exit 1; \
	  if [ "$$os" = windows ]; then (cd dist && zip -q $$(basename $$out).zip $$(basename $$out)$$ext && rm $$(basename $$out)$$ext) && \
	    zip -q -j $$out.zip build/ffmpeg/windows-$$arch/ffmpeg.exe build/ffmpeg/windows-$$arch/ffprobe.exe build/ffmpeg/windows-$$arch/VERSION deploy/macos/FFMPEG-LICENSE.md; \
	  elif [ "$$os" = darwin ]; then tar -czf $$out.tar.gz -C dist $$(basename $$out) -C ../build/ffmpeg/darwin-$$arch ffmpeg ffprobe VERSION -C ../../../deploy/macos FFMPEG-LICENSE.md && rm $$out; \
	  else tar -C dist -czf $$out.tar.gz $$(basename $$out) && rm $$out; fi; \
	done
	@ls -la dist

# VERSION must be given here: its default, git describe, is never a new tag.
release-tag: ## Tag origin/main as a release and push it, which starts release.yml (VERSION=vX.Y.Z[-rc.N])
	@$(if $(filter command line,$(origin VERSION)),,echo "usage: make release-tag VERSION=vX.Y.Z" >&2; exit 1)
	deploy/release/tag.sh $(VERSION)

fixture: ## Render the synthetic test library into data/fixture (needs ffmpeg; see deploy/fixture/fixture.sh)
	deploy/fixture/fixture.sh media

fixture-serve: build ## Run the server on the fixture library at :27588 and create its test accounts
	deploy/fixture/fixture.sh serve

streaming-check: ## Characterize simultaneous streams and bandwidth changes on an owned fictional fixture
	python3 deploy/fixture/streaming.py --output build/streaming/report.json $(STREAMING_ARGS)

test: ## Run Go tests and the web typecheck
	cd server && go test ./...
	cd web && pnpm typecheck

bench: ## Time history, home and DVR queries over large fixtures, failing over budget
	cd server && WMM_ACTIVITY_PLAYS=250000 WMM_ACTIVITY_STRICT=1 go test ./internal/activity -run TestHistorySpeed -count=1 -v
	cd server && WMM_HOME_STRICT=1 go test ./internal/api -run TestHomeSpeed -count=1 -v
	cd server && WMM_DVR_STRICT=1 go test ./internal/livetv/dvr -run TestDVRSpeed -count=1 -v

server-deps: ## Report the server's outdated Go modules, Go version and pinned tools; UPDATE=patch|minor updates them and runs the checks
	server/scripts/update-deps.sh $(UPDATE)

android-verify-metadata: ## Rewrite android/gradle/verification-metadata.xml after an Android library, plugin or Gradle change; review its diff
	android/gradle/update-verification-metadata.sh

agents-check: ## Check that the AGENTS.md files name paths and make targets that exist
	python3 -I deploy/ci/check-agent-docs.py

lint: ## Lint Go and web code
	cd server && go vet ./... && golangci-lint run ./...
	cd web && pnpm lint

docker: ## Build the Docker image for this machine
	docker build -f deploy/Dockerfile --build-arg VERSION=$(VERSION) -t watchmymedia:dev .

docker-smoke: ## Check the built image: ffmpeg's hardware encoders, the entrypoint's groups, the server starts
	deploy/ci/docker-smoke.sh watchmymedia:dev

docker-multiarch: ## Build (without pushing) the linux/amd64 and linux/arm64 images
	docker buildx build -f deploy/Dockerfile --platform linux/amd64,linux/arm64 --build-arg VERSION=$(VERSION) -t watchmymedia:dev .

docker-up: ## Start via docker compose
	docker compose -f deploy/docker-compose.yml up -d --build

clean: ## Remove build outputs
	rm -rf bin dist web/dist server/tmp
	find $(WEB_DIST) -mindepth 1 ! -name .gitkeep -delete

# Releases, the downloads Worker, the site and the brand sources are kept outside the public
# repository; their targets are in Makefile.private where it exists.
-include Makefile.private
