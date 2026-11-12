package scanner

// Event payloads published on the bus and streamed over SSE. Each is its own
// type because the SSE layer maps Go types to event names.

type ScanStarted struct {
	LibraryID int64 `json:"libraryId"`
	Total     int   `json:"total" doc:"Video files found in the library tree."`
}

type ScanProgress struct {
	LibraryID int64  `json:"libraryId"`
	Scanned   int    `json:"scanned"`
	Total     int    `json:"total"`
	Current   string `json:"current,omitempty" doc:"Path of the file most recently processed, relative to the library root."`
}

type ScanFinished struct {
	LibraryID  int64 `json:"libraryId"`
	Added      int   `json:"added"`
	Updated    int   `json:"updated"`
	Unchanged  int   `json:"unchanged"`
	Reparsed   int   `json:"reparsed" doc:"Unchanged files whose names now read differently, such as date-named episodes; unmatched ones are matched again."`
	Missing    int   `json:"missing" doc:"Files that were present before and are now gone."`
	Skipped    int   `json:"skipped" doc:"Files still changing on disk; the scan will run again."`
	Failed     int   `json:"failed" doc:"Files ffprobe could not read; stored with an error."`
	Restored   int   `json:"restored" doc:"Files that were missing and are back unchanged."`
	Partial    bool  `json:"partial" doc:"Only the paths the watcher saw change were read, not the whole library."`
	DurationMs int64 `json:"durationMs"`
	// Announced is set when the scan sent library.changed itself, so
	// matching that follows does not send it again.
	Announced bool `json:"-"`
}

type ScanFailed struct {
	LibraryID int64  `json:"libraryId"`
	Error     string `json:"error"`
}

// WatchStatus is how much of a library the filesystem watcher covers.
type WatchStatus struct {
	State     string `json:"state" enum:"watching,partial,off" doc:"watching: every folder; partial: the system's limit on watches was reached; off: the folder is unreachable or watching is unavailable. Changes the watcher does not see are found by the scheduled scan."`
	Folders   int    `json:"folders" doc:"Folders watched."`
	Unwatched int    `json:"unwatched" doc:"Folders found but not watched."`
}

type LibraryChanged struct {
	LibraryID int64 `json:"libraryId"`
}

type Ping struct {
	Time string `json:"time"`
}
