package db

import "time"

// TimeLayout matches SQLite's strftime('%Y-%m-%dT%H:%M:%fZ') so Go-written and
// SQL-written timestamps sort and compare as plain strings.
const TimeLayout = "2006-01-02T15:04:05.000Z"

// FormatTime renders t in the database's timestamp format (UTC, millisecond).
func FormatTime(t time.Time) string { return t.UTC().Format(TimeLayout) }

// ParseTime reads a database timestamp. Invalid or empty input yields the zero time.
func ParseTime(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// ParseTimePtr is ParseTime for nullable columns.
func ParseTimePtr(s *string) *time.Time {
	if s == nil {
		return nil
	}
	t := ParseTime(*s)
	if t.IsZero() {
		return nil
	}
	return &t
}
