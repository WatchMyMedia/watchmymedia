//go:build race

package db

// raceDetector is set under -race, which makes SQLite (Go code here) tens
// of times slower, so speed tests shrink and do not time.
const raceDetector = true
