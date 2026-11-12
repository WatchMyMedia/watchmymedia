//go:build !darwin && !windows

package ffexec

import "os"

// Track does nothing here: the parent-death signal (Linux) kills what is left.
func Track(string) error { return nil }

// KillLeftovers does nothing here: nothing is left to kill.
func KillLeftovers(string) int { return 0 }

func started(*os.Process) {}

func exited(*os.Process) {}
