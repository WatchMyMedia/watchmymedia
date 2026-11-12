package hwdevice

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync/atomic"
	"testing"
	"testing/synctest"
)

func TestDirectXInventoryKeepsOrdinalsWhenSoftwareIsFiltered(t *testing.T) {
	t.Parallel()
	rows := []directXDescription{{Name: "software", Software: true}, {Name: "Fictional integrated", VendorID: 0x8086}, {Software: true}, {Name: "Fictional discrete", VendorID: 0x1002}}
	got, err := collectDirectX(func(i int) (directXDescription, error) {
		if i == len(rows) {
			return directXDescription{}, errNoMoreAdapters
		}
		return rows[i], nil
	})
	if err != nil || len(got) != 2 || got[0].Index != 1 || got[1].Index != 3 || got[0].VendorID != 0x8086 || got[1].VendorID != 0x1002 {
		t.Fatalf("renumbered/filter-corrupted adapters: %+v, %v", got, err)
	}
	if _, err := collectDirectX(func(i int) (directXDescription, error) {
		if i == 0 {
			return rows[1], nil
		}
		return directXDescription{}, errors.New("driver failed")
	}); err == nil {
		t.Fatal("accepted partial driver failure")
	}
}

func TestInventoryPreservesTheHealthyNamespace(t *testing.T) {
	t.Parallel()
	i := discover(t.Context(), func(_ context.Context, namespace string) ([]byte, error) {
		if namespace == "cuda" {
			return nil, errors.New(strings.Repeat("driver failed\n", 1000))
		}
		return json.Marshal(listing{Adapters: []Adapter{{Namespace: "directx", Index: 3, Name: "Fictional adapter", VendorID: 0x8086}}})
	}, make(chan struct{}, 2))
	if len(i.Adapters) != 1 || i.Adapters[0].Index != 3 || !i.Diagnostics[0].OK || i.Diagnostics[1].OK || len([]rune(i.Diagnostics[1].Detail)) > 512 || strings.Contains(i.Diagnostics[1].Detail, "\n") {
		t.Fatalf("lost independent/bounded results: %+v", i)
	}
}

func TestInventoryDeadlineBoundsCallersAndOutstandingWorkers(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		slots := make(chan struct{}, 2)
		release := make(chan struct{})
		defer close(release)
		var calls atomic.Int32
		run := func(context.Context, string) ([]byte, error) {
			calls.Add(1)
			<-release
			return []byte(`{"adapters":[]}`), nil
		}
		for range 2 {
			i := discover(t.Context(), run, slots)
			for _, d := range i.Diagnostics {
				if d.OK || !strings.Contains(d.Detail, "deadline exceeded") {
					t.Fatalf("did not finish at the deadline: %+v", i)
				}
			}
		}
		if calls.Load() != 2 {
			t.Fatalf("spawned %d workers while earlier drivers were stuck", calls.Load())
		}
	})
}

func TestInventoryCancellationDoesNotLaunchAWorker(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	var calls atomic.Int32
	i := discover(ctx, func(context.Context, string) ([]byte, error) { calls.Add(1); return nil, nil }, make(chan struct{}, 2))
	if calls.Load() != 0 || len(i.Diagnostics) != 2 || i.Diagnostics[0].OK || i.Diagnostics[1].OK {
		t.Fatalf("canceled enumeration launched work: %+v", i)
	}
}

func TestInventoryRejectsMalformedOrAmbiguousWorkers(t *testing.T) {
	t.Parallel()
	for _, data := range []string{
		`null`, `{}`, `{"adapters":null}`, `{"adapters":[{"namespace":"cuda"}]}`,
		`{"adapters":[{"namespace":"directx","index":0}]}`,
		`{"adapters":[{"namespace":"cuda","index":-1}]}`,
		`{"adapters":[{"namespace":"cuda","index":0},{"namespace":"cuda","index":0}]}`,
		strings.Repeat("x", maxOutput+1),
	} {
		if _, err := decodeListing([]byte(data), "cuda"); err == nil {
			t.Fatal("accepted malformed, duplicate, missing or wrong-namespace ordinal")
		}
	}
	data, err := json.Marshal(listing{Adapters: []Adapter{{Namespace: "cuda", Index: 3, Name: strings.Repeat("é", 200) + "\n"}, {Namespace: "cuda", Index: 0}}})
	if err != nil {
		t.Fatal(err)
	}
	l, err := decodeListing(data, "cuda")
	if err != nil || len(l.Adapters) != 2 || l.Adapters[0].Index != 0 || l.Adapters[1].Index != 3 || len([]rune(l.Adapters[1].Name)) != 128 || l.Adapters[0].Name == "" {
		t.Fatalf("lost zero/gaps or unbounded name: %+v %v", l, err)
	}
}

func TestWorkerOutputLimitCannotBeBypassedByIOCopy(t *testing.T) {
	t.Parallel()
	var out limitedOutput
	_, err := io.Copy(&out, io.LimitReader(strings.NewReader(strings.Repeat("x", maxOutput+1)), maxOutput+1))
	if err == nil || len(out.Bytes()) > maxOutput {
		t.Fatal("io.Copy bypassed the subprocess output cap")
	}
}
