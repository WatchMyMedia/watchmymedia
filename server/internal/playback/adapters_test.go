package playback

import (
	"context"
	"testing"
	"time"

	"watchmymedia/server/internal/hwdevice"
)

func TestObsoleteAdapterDiscoveryCannotReplaceTheCurrentSnapshot(t *testing.T) {
	t.Parallel()
	m := NewManager("watchmymedia-test-missing-ffmpeg", t.TempDir(), Capabilities{}, time.Minute, 1, testLog)
	release := make(chan struct{})
	defer func() {
		m.cancel()
		select {
		case <-release:
		default:
			close(release)
		}
		m.detectWG.Wait()
	}()
	started := make(chan context.Context, 1)
	m.SetAdapterScanner(func(ctx context.Context) hwdevice.Inventory {
		started <- ctx
		<-release
		return hwdevice.Inventory{Supported: true, Adapters: []hwdevice.Adapter{{Namespace: "cuda", Index: 2, Name: "obsolete adapter"}}}
	})
	m.DetectInBackground("software", "")
	old := <-started
	if i := m.HardwareAdapters(); i == nil || !i.Checking {
		t.Fatal("did not announce inventory discovery")
	}
	m.SetAdapterScanner(func(context.Context) hwdevice.Inventory {
		return hwdevice.Inventory{Supported: true, Adapters: []hwdevice.Adapter{{Namespace: "directx", Index: 3, Name: "current adapter"}}}
	})
	m.DetectInBackground("software", "")
	m.CapabilitiesContext(t.Context())
	if old.Err() == nil {
		t.Fatal("old detection was not canceled")
	}
	close(release)
	m.detectWG.Wait()
	i := m.HardwareAdapters()
	if i == nil || i.Checking || len(i.Adapters) != 1 || i.Adapters[0].Name != "current adapter" {
		t.Fatalf("stale inventory replaced the current generation: %+v", i)
	}
	i.Adapters[0].Name = "changed by a reader"
	if m.HardwareAdapters().Adapters[0].Name != "current adapter" {
		t.Fatal("inventory readers share mutable slices")
	}
}
