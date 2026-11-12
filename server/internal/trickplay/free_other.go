//go:build !windows

package trickplay

import "watchmymedia/server/internal/bgwork"

var freeBytes = bgwork.FreeBytes
