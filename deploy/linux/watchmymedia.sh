#!/bin/sh
# Runs the WatchMyMedia server with the folders this package uses. The
# service starts it this way, and so does `watchmymedia backup` at a prompt,
# so both agree on where the data is. Each value is set only when it is unset
# or empty, so one from the environment (`systemctl edit watchmymedia`) wins.
# build.py writes deb or rpm in place of @INSTALL@.
: "${WATCHMYMEDIA_DATA_DIR:=/var/lib/watchmymedia}"
: "${WATCHMYMEDIA_CONFIG:=/etc/watchmymedia/config.yaml}"
: "${WATCHMYMEDIA_TRANSCODE_DIR:=/var/cache/watchmymedia}"
: "${WATCHMYMEDIA_INSTALL:=@INSTALL@}"
export WATCHMYMEDIA_DATA_DIR WATCHMYMEDIA_CONFIG WATCHMYMEDIA_TRANSCODE_DIR WATCHMYMEDIA_INSTALL
exec /usr/lib/watchmymedia/watchmymedia "$@"
