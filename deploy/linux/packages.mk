# The make targets for the server's Debian, Ubuntu and Fedora packages. The
# root Makefile includes this file, so they run from the repository's root as
# `make linux-packages` does. They are here, not there, so that CI's Linux
# package jobs can run for a change to them without running for every other
# change to the Makefile (deploy/AGENTS.md, Linux packages).
#
# The packages are the Linux binaries with the pinned ffmpeg, a systemd service
# and its user, as a .deb and an .rpm for each of amd64 and arm64.
#
# LINUX_REPOSITORY_KEY  the armored public key of the apt and dnf repositories,
#                       which the packages carry beside their repository entry.
#                       Empty builds packages with neither, which install and
#                       never look for updates.
# LINUX_SIGNING_KEY     the OpenPGP secret key the .rpm files are signed with
#                       (its passphrase in NFPM_RPM_PASSPHRASE). Empty leaves
#                       them unsigned.
# LINUX_SIGNING_KEY_ID  the signing subkey's id, 16 hex digits, needed with a
#                       signing key: left to itself nfpm picks the key's last
#                       signing subkey, and the repositories' lists have a
#                       subkey of their own.
.PHONY: ffmpeg-linux linux-packages linux-smoke-packages linux-packages-smoke

LINUX_PACKAGES_OUT ?= build/linux-packages
LINUX_REPOSITORY_KEY ?= deploy/linux/repository-key.asc
LINUX_SIGNING_KEY ?=
LINUX_SIGNING_KEY_ID ?=

ffmpeg-linux: ## Download the pinned ffmpeg build for Linux into build/ffmpeg/linux-{amd64,arm64}
	deploy/macos/fetch-ffmpeg.sh linux-amd64
	deploy/macos/fetch-ffmpeg.sh linux-arm64

linux-packages: web ffmpeg-linux ## Build the server's .deb and .rpm for amd64 and arm64 -> build/linux-packages
	@for arch in amd64 arm64; do \
	  echo "building build/linux/$$arch/watchmymedia"; \
	  (cd server && CGO_ENABLED=0 GOOS=linux GOARCH=$$arch go build -trimpath -ldflags="$(LDFLAGS)" -o ../build/linux/$$arch/watchmymedia ./cmd/server) || exit 1; \
	done
	python3 -I deploy/linux/build.py --version $(VERSION) --out $(LINUX_PACKAGES_OUT) \
	  $(if $(LINUX_REPOSITORY_KEY),--repository-key $(LINUX_REPOSITORY_KEY)) \
	  $(if $(LINUX_SIGNING_KEY),--signing-key $(LINUX_SIGNING_KEY) --signing-key-id $(LINUX_SIGNING_KEY_ID))

# What linux-packages-smoke installs: an older build to update from and a
# current one, both made as a release will be: with the repository entry and
# its key, and the .rpm signed by a signing subkey. The subkey is a throwaway
# key's, made here, since the real one is only in the release. Nothing here is
# published.
linux-smoke-packages: web ffmpeg-linux ## Build an older and a current set of Linux packages, signed with a throwaway key -> build/linux-smoke
	deploy/linux/throwaway-key.sh build/linux-smoke/key
	$(MAKE) -o web -o ffmpeg-linux linux-packages VERSION=v0.0.1 LINUX_PACKAGES_OUT=build/linux-smoke/older \
	  LINUX_SIGNING_KEY=build/linux-smoke/key/secret.asc LINUX_SIGNING_KEY_ID=$$(cat build/linux-smoke/key/signing-key-id)
	$(MAKE) -o web -o ffmpeg-linux linux-packages VERSION=v0.0.2 LINUX_PACKAGES_OUT=build/linux-smoke/current \
	  LINUX_SIGNING_KEY=build/linux-smoke/key/secret.asc LINUX_SIGNING_KEY_ID=$$(cat build/linux-smoke/key/signing-key-id)

# IMAGE is one of debian:12, debian:13, ubuntu:24.04, ubuntu:26.04, fedora:44
# and fedora:45; the container is this machine's architecture.
IMAGE ?= debian:13
linux-packages-smoke: ## Install, update and remove build/linux-smoke's packages in a systemd container of IMAGE (default debian:13)
	deploy/linux/smoke.sh $(IMAGE)
