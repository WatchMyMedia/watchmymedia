package db

// BoolInt is a bool as SQLite stores it: 1 or 0.
func BoolInt(b bool) int64 {
	if b {
		return 1
	}
	return 0
}
