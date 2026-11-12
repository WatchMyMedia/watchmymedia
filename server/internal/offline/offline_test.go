package offline

import (
	"testing"
	"time"
)

func TestAllowed(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		access        string
		admin, member bool
	}{{AccessNone, false, false}, {AccessAdmins, true, false}, {AccessEveryone, true, true}, {"", false, false}} {
		if Allowed(c.access, true) != c.admin || Allowed(c.access, false) != c.member {
			t.Errorf("%q: admin %v, member %v", c.access, Allowed(c.access, true), Allowed(c.access, false))
		}
	}
	if id := NewID(); len(id) != 16 || id == NewID() {
		t.Errorf("id %q", id)
	}
}

// The transfers list counts bytes and keeps the furthest progress, and
// forgets a transfer a minute after its last request.
func TestTransfers(t *testing.T) {
	t.Parallel()
	now := time.Unix(1_800_000_000, 0)
	tr := NewTransfers(func() time.Time { return now })
	info := Info{Record: "a", UserID: 2}
	tr.Add(info, 1000, 0.5)
	now = now.Add(time.Second)
	tr.Add(info, 3000, 0.25)
	got := tr.List(0)
	if len(got) != 1 || got[0].BytesSent != 4000 || got[0].Progress != 0.5 || got[0].Bandwidth != 4000*8 {
		t.Fatalf("transfers = %+v", got)
	}
	if len(tr.List(3)) != 0 || len(tr.List(2)) != 1 {
		t.Error("filtered by person wrongly")
	}
	tr.Forget("a")
	if len(tr.List(0)) != 0 {
		t.Error("a forgotten transfer is still listed")
	}
	tr.Add(info, 10, -1)
	now = now.Add(Window + time.Second)
	if len(tr.List(0)) != 0 {
		t.Error("an idle transfer is still listed")
	}
}
