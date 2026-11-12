//go:build race

package api

// raceEnabled is true under the race detector, whose slowdown makes speed
// budgets meaningless.
const raceEnabled = true
