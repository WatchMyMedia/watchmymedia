package hdhomerun

import (
	"log/slog"
	"strings"
)

// Secret is the tuner's DeviceAuth, which SiliconDust's listings service
// takes as proof of the tuner. It prints, logs and marshals as [redacted],
// so it can't reach a log line, an error or a reply by accident; only
// Reveal gives it, for the one request that sends it.
type Secret struct{ v string }

const redacted = "[redacted]"

func (s Secret) String() string               { return redacted }
func (s Secret) GoString() string             { return redacted }
func (s Secret) LogValue() slog.Value         { return slog.StringValue(redacted) }
func (s Secret) MarshalJSON() ([]byte, error) { return []byte(`"` + redacted + `"`), nil }

// Reveal returns the value, to put in the request that needs it.
func (s Secret) Reveal() string { return s.v }

// Empty says whether there is none, as on firmware without DeviceAuth.
func (s Secret) Empty() bool { return s.v == "" }

// Join concatenates several tuners' DeviceAuth, as SiliconDust's
// documentation says to for a household with more than one.
func Join(secrets ...Secret) Secret {
	var out strings.Builder
	for _, s := range secrets {
		out.WriteString(s.v)
	}
	return Secret{out.String()}
}
