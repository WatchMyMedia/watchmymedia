package hwdevice

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"runtime"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode"

	"watchmymedia/server/internal/ffexec"
)

const (
	maxAdapters        = 64
	maxOutput          = 64 << 10
	enumerationTimeout = 5 * time.Second
)

// Adapter indices belong to one native namespace, not to a universal GPU list.
// An inventory entry does not imply that FFmpeg can encode/decode on the device.
type Adapter struct {
	Namespace string `json:"namespace" enum:"cuda,directx"`
	Index     int    `json:"index" minimum:"0" maximum:"2147483647"`
	Name      string `json:"name" maxLength:"128"`
	VendorID  uint32 `json:"vendorId,omitempty" required:"false"`
}

type EnumerationDiagnostic struct {
	Namespace string `json:"namespace" enum:"cuda,directx"`
	OK        bool   `json:"ok"`
	Detail    string `json:"detail,omitempty" required:"false" maxLength:"512"`
}

// Inventory is private to administrators/local reports. Never put it in the
// public playback capabilities or persist it in the encoder detection cache.
type Inventory struct {
	Supported   bool                    `json:"supported"`
	Checking    bool                    `json:"checking,omitempty" required:"false"`
	Adapters    []Adapter               `json:"adapters" nullable:"false" maxItems:"128"`
	Diagnostics []EnumerationDiagnostic `json:"diagnostics" nullable:"false" maxItems:"2"`
}

func (i Inventory) Clone() Inventory {
	i.Adapters = append([]Adapter{}, i.Adapters...)
	i.Diagnostics = append([]EnumerationDiagnostic{}, i.Diagnostics...)
	return i
}

type Scanner func(context.Context) Inventory

// Slots stay occupied until a child has actually exited, even if a driver
// ignores process termination. Repeated admin checks cannot accumulate workers.
var inventorySlots = make(chan struct{}, 2)

// NewScanner runs only our own executable's native worker. Callers provide the
// production executable explicitly; package tests must never re-run themselves.
func NewScanner(executable string) Scanner {
	if runtime.GOOS != "windows" {
		return nil
	}
	return func(ctx context.Context) Inventory { return discover(ctx, processRunner(executable), inventorySlots) }
}

type listing struct {
	Adapters []Adapter `json:"adapters"`
	Error    string    `json:"error,omitempty"`
}
type enumerationRun func(context.Context, string) ([]byte, error)
type enumerationResult struct {
	data []byte
	err  error
}

func discover(ctx context.Context, run enumerationRun, slots chan struct{}) Inventory {
	i := Inventory{Supported: true, Adapters: []Adapter{}, Diagnostics: make([]EnumerationDiagnostic, 2)}
	results := make([][]Adapter, 2)
	var wg sync.WaitGroup
	for n, namespace := range []string{"directx", "cuda"} {
		wg.Go(func() {
			workerCtx, cancel := context.WithTimeout(ctx, enumerationTimeout)
			defer cancel()
			data, err := boundedRun(workerCtx, namespace, run, slots)
			var l listing
			if err == nil {
				l, err = decodeListing(data, namespace)
			}
			i.Diagnostics[n] = EnumerationDiagnostic{Namespace: namespace, OK: err == nil}
			if err != nil {
				i.Diagnostics[n].Detail = cleanText(err.Error(), 512)
				return
			}
			results[n] = l.Adapters
		})
	}
	wg.Wait()
	for _, adapters := range results {
		i.Adapters = append(i.Adapters, adapters...)
	}
	return i
}

func boundedRun(ctx context.Context, namespace string, run enumerationRun, slots chan struct{}) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	select {
	case slots <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	result := make(chan enumerationResult, 1)
	go func() {
		defer func() { <-slots }()
		b, err := run(ctx, namespace)
		result <- enumerationResult{b, err}
	}()
	select {
	case r := <-result:
		return r.data, r.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func processRunner(executable string) enumerationRun {
	return func(ctx context.Context, namespace string) ([]byte, error) {
		if executable == "" {
			return nil, errors.New("server executable is unavailable")
		}
		var out limitedOutput
		cmd := ffexec.Command(ctx, executable, "gpu-adapters", "--native", namespace)
		cmd.Stdout = &out
		if err := cmd.Run(); err != nil {
			return nil, err
		}
		return out.Bytes(), nil
	}
}

// Do not embed bytes.Buffer: its promoted ReadFrom would bypass Write's cap
// when exec copies a child's stdout with io.Copy.
type limitedOutput struct{ buffer bytes.Buffer }

func (b *limitedOutput) Bytes() []byte { return b.buffer.Bytes() }

func (b *limitedOutput) Write(p []byte) (int, error) {
	if len(p) > maxOutput-b.buffer.Len() {
		return 0, errors.New("adapter worker output exceeded its limit")
	}
	return b.buffer.Write(p)
}

func decodeListing(data []byte, namespace string) (listing, error) {
	var l listing
	if len(data) > maxOutput {
		return l, errors.New("adapter worker output exceeded its limit")
	}
	var wire struct {
		Adapters *[]struct {
			Namespace string `json:"namespace"`
			Index     *int   `json:"index"`
			Name      string `json:"name"`
			VendorID  uint32 `json:"vendorId"`
		} `json:"adapters"`
		Error string `json:"error"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return l, fmt.Errorf("decode adapter worker: %w", err)
	}
	if wire.Error != "" {
		return l, errors.New(cleanText(wire.Error, 512))
	}
	if wire.Adapters == nil {
		return l, errors.New("adapter worker omitted its list")
	}
	if len(*wire.Adapters) > maxAdapters {
		return l, errors.New("too many adapters")
	}
	seen := map[int]bool{}
	l.Adapters = []Adapter{}
	for _, a := range *wire.Adapters {
		if a.Namespace != namespace || a.Index == nil || *a.Index < 0 || *a.Index > 2147483647 || seen[*a.Index] {
			return listing{}, errors.New("invalid or duplicate adapter ordinal/namespace")
		}
		seen[*a.Index] = true
		l.Adapters = append(l.Adapters, Adapter{Namespace: namespace, Index: *a.Index, Name: adapterName(a.Name, namespace, *a.Index), VendorID: a.VendorID})
	}
	slices.SortFunc(l.Adapters, func(a, b Adapter) int { return a.Index - b.Index })
	return l, nil
}

func cleanText(s string, limit int) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, strings.ToValidUTF8(s, "�"))
	r := []rune(strings.TrimSpace(s))
	if len(r) > limit {
		r = r[:limit]
	}
	return string(r)
}
func adapterName(s, namespace string, index int) string {
	if s = cleanText(s, 128); s != "" {
		return s
	}
	return fmt.Sprintf("%s device %d", namespace, index)
}

// WriteNative is the child-only entry point. The parent owns its deadline/job.
func WriteNative(namespace string) ([]byte, error) {
	if namespace != "cuda" && namespace != "directx" {
		return nil, errors.New("unknown adapter namespace")
	}
	adapters, err := nativeAdapters(namespace)
	l := listing{Adapters: append([]Adapter{}, adapters...)}
	if err != nil {
		l.Adapters = []Adapter{}
		l.Error = cleanText(err.Error(), 512)
	}
	return json.Marshal(l)
}

var errNoMoreAdapters = errors.New("no more adapters")

type directXDescription struct {
	Name     string
	VendorID uint32
	Software bool
}

func collectDirectX(read func(int) (directXDescription, error)) ([]Adapter, error) {
	adapters := []Adapter{}
	for index := 0; index <= maxAdapters; index++ {
		d, err := read(index)
		if errors.Is(err, errNoMoreAdapters) {
			return adapters, nil
		}
		if err != nil {
			return nil, err
		}
		if index == maxAdapters {
			return nil, errors.New("too many DirectX adapters")
		}
		if !d.Software {
			adapters = append(adapters, Adapter{Namespace: "directx", Index: index, Name: adapterName(d.Name, "directx", index), VendorID: d.VendorID})
		}
	}
	return adapters, nil
}
