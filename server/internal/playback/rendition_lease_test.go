package playback

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"testing/synctest"
	"time"
)

// Model admitted workers without an encoder so cancellation and admission
// interleavings can be driven by channels, without sleeps or CPU-speed races.
func leasedTestVOD(t *testing.T, m *Manager, user int64) *vodState {
	t.Helper()
	spec := Spec{UserID: user, FileID: 1, ReserveSlot: true, SharedOutput: true,
		VOD: &VODOptions{DurationMs: 12_000}}
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ctx, err := m.newSessionLocked(spec, "libx264")
	if err != nil {
		t.Fatal(err)
	}
	v, err := newVOD(m, s, ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	s.vod = v
	s.starting.Store(true)
	m.sessions[s.ID] = s
	go func() { <-ctx.Done(); close(s.done) }()
	return v
}

func sharedTestRequest(v *vodState, key string, produce func(context.Context) ([]byte, error)) <-chan error {
	result := make(chan error, 1)
	go func() {
		b, release, err := v.sharedRendition(context.Background(), key, produce, func([]byte) error { return nil })
		if release != nil {
			release()
		}
		if err == nil && string(b) != key {
			err = errors.New("shared response has wrong bytes")
		}
		result <- err
	}()
	return result
}

func TestSharedRenditionStopViewerRetainsWorkerAndAdmissionForPeers(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 2, testLog)
		defer m.StopAll()
		first, peer := leasedTestVOD(t, m, 1), leasedTestVOD(t, m, 2)
		var original, followers []<-chan error
		var finish []chan struct{}
		calls := 0
		for _, key := range []string{"cold/one", "cold/two"} {
			done := make(chan struct{})
			finish = append(finish, done)
			produce := func(work context.Context) ([]byte, error) {
				calls++
				select {
				case <-done:
					return []byte(key), nil
				case <-work.Done():
					return nil, work.Err()
				case <-first.ctx.Done():
					return nil, ErrStopped
				}
			}
			original = append(original, sharedTestRequest(first, key, produce))
			synctest.Wait()
			followers = append(followers, sharedTestRequest(peer, key, produce))
			synctest.Wait()
		}
		if stats := m.RenditionStats(); stats.Flights != 2 || stats.Followers != 4 {
			t.Fatalf("requests did not join two shared flights: %+v", stats)
		}
		m.Stop(first.s.ID)
		synctest.Wait()
		for _, result := range original {
			if err := <-result; !errors.Is(err, ErrStopped) {
				t.Fatalf("stopped viewer request = %v", err)
			}
		}
		if _, ok := m.Get(first.s.ID); ok || len(m.Sessions()) != 1 {
			t.Fatal("stopped viewer is still authorized")
		}
		if _, _, err := first.s.VODFile(t.Context(), VODMaster); !errors.Is(err, ErrStopped) {
			t.Fatalf("stopped viewer served a master: %v", err)
		}
		if first.ctx.Err() != nil || m.Claimed() != 2 || m.Running() != 2 {
			t.Fatal("viewer stop canceled shared work or released its capacity")
		}
		if release, err := m.ReserveLive(t.Context()); !errors.Is(err, ErrTooManySessions) {
			if release != nil {
				release()
			}
			t.Fatalf("Live TV stole a leased producer slot: %v", err)
		}
		if release, err := m.leaseRendition(first.s); !errors.Is(err, ErrStopped) {
			if release != nil {
				release()
			}
			t.Fatalf("new work borrowed a retired worker: %v", err)
		}
		close(finish[0])
		if err := <-followers[0]; err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		if first.ctx.Err() != nil || m.Claimed() != 2 {
			t.Fatal("first completed flight canceled a second flight's worker")
		}
		close(finish[1])
		if err := <-followers[1]; err != nil {
			t.Fatal(err)
		}
		synctest.Wait()
		if first.ctx.Err() == nil || m.Claimed() != 1 || calls != 2 {
			t.Fatalf("worker did not release or peer restarted production: claims=%d calls=%d", m.Claimed(), calls)
		}
		if _, err := os.Stat(first.s.Dir); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("retired output was not removed: %v", err)
		}
		release, err := m.ReserveLive(t.Context())
		if err != nil {
			t.Fatalf("finished producer did not release admission: %v", err)
		}
		release()
	})
}

func TestSharedRenditionLeaseEndsOnLastViewerShutdownOrStoragePressure(t *testing.T) {
	for _, reason := range []string{"last viewer", "shutdown", "storage"} {
		t.Run(reason, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 2, testLog)
				defer m.StopAll()
				first, peer := leasedTestVOD(t, m, 1), leasedTestVOD(t, m, 2)
				produce := func(work context.Context) ([]byte, error) {
					select {
					case <-work.Done():
						return nil, work.Err()
					case <-first.ctx.Done():
						return nil, ErrStopped
					}
				}
				original := sharedTestRequest(first, "cold", produce)
				synctest.Wait()
				follower := sharedTestRequest(peer, "cold", produce)
				synctest.Wait()
				m.Stop(first.s.ID)
				synctest.Wait()
				if err := <-original; !errors.Is(err, ErrStopped) {
					t.Fatal(err)
				}
				switch reason {
				case "last viewer":
					m.Stop(peer.s.ID)
				case "shutdown":
					m.StopAll()
					if m.Claimed() != 0 {
						t.Fatal("shutdown returned before producer leases released")
					}
				case "storage":
					if err := os.WriteFile(filepath.Join(first.s.Dir, "piece.m4s"), []byte("over budget"), 0o600); err != nil {
						t.Fatal(err)
					}
					m.SetResourceLimits(ResourceLimits{MaxSessionBytes: 1})
					m.checkResources()
					if !errors.Is(first.s.ResourceError(), ErrStorageBudget) {
						t.Fatal("retired producer escaped storage enforcement")
					}
				}
				if err := <-follower; err == nil {
					t.Fatal("canceled producer returned successful media")
				}
				synctest.Wait()
				if first.ctx.Err() == nil || len(m.retiring) != 0 {
					t.Fatal("producer lease survived cancellation")
				}
				if stats := m.RenditionStats(); stats.Flights != 0 || stats.Followers != 0 || stats.Bytes != 0 {
					t.Fatalf("canceled work was retained: %+v", stats)
				}
				m.StopAll()
				synctest.Wait()
				if m.Claimed() != 0 {
					t.Fatal("shutdown leaked capacity")
				}
			})
		})
	}
}
