//go:build race

package activity

// raceDetector is set under -race, which makes SQLite (Go code here) tens
// of times slower, so speed tests do not run.
const raceDetector = true
