//go:build !unix

package scanner

import (
	"log/slog"
	"math"
)

// watchBudget is unbounded: watching stops only when the system refuses.
func watchBudget(*slog.Logger) int { return math.MaxInt }
