package dvr

// Standalone server binaries, especially on Windows, cannot rely on a Go
// installation or OS IANA zone files. Repeat schedules keep the viewer's
// wall clock across daylight saving changes on every supported server.
import _ "time/tzdata"
