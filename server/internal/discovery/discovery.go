// Package discovery announces the server on the local network with
// multicast DNS (DNS-SD), so TV and phone apps can list servers at home
// without anyone typing an address. The service is
// "<server name>._watchmymedia._tcp.local." with TXT records:
//
//	id       the server's stable random ID (GET /api/server returns the same)
//	version  the server version
//	api      the API level clients check against
//	path     "/" (the API lives under <path>api/)
//
// It only works where the server shares the LAN's multicast domain: a native
// install, or Docker with network_mode: host.
package discovery

import (
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/brutella/dnssd"
	dnslog "github.com/brutella/dnssd/log"
)

const ServiceType = "_watchmymedia._tcp"

// Info is what is announced.
type Info struct {
	ID       string
	Name     string
	Version  string
	APILevel int
	Port     int
}

// Advertiser keeps the service announced until its context ends.
type Advertiser struct {
	mu   sync.Mutex
	rp   dnssd.Responder
	hdl  dnssd.ServiceHandle
	info Info
	log  *slog.Logger
	done chan struct{}
}

// New prepares the announcement; Run sends it.
func New(info Info, log *slog.Logger) (*Advertiser, error) {
	// The library logs to stdout by default; send it to our log instead.
	dnslog.Info.SetOutput(logWriter{log})
	rp, err := dnssd.NewResponder()
	if err != nil {
		return nil, err
	}
	a := &Advertiser{rp: rp, info: info, log: log, done: make(chan struct{})}
	if err := a.add(); err != nil {
		return nil, err
	}
	return a, nil
}

func (a *Advertiser) add() error {
	sv, err := dnssd.NewService(dnssd.Config{
		Name: instanceName(a.info.Name),
		Type: ServiceType,
		// Our own host name, so the operating system's record for its name
		// (which mDNSResponder or Avahi already answers) is left alone.
		Host: "watchmymedia-" + shortID(a.info.ID),
		Port: a.info.Port,
		Text: map[string]string{
			"id":      a.info.ID,
			"version": a.info.Version,
			"api":     fmt.Sprint(a.info.APILevel),
			"path":    "/",
		},
	})
	if err != nil {
		return err
	}
	a.hdl, err = a.rp.Add(sv)
	return err
}

// Run announces the service and answers queries until ctx ends, then says
// goodbye so browsers drop it at once.
func (a *Advertiser) Run(ctx context.Context) {
	defer close(a.done)
	a.log.Info("announcing on the local network", "service", instanceName(a.info.Name)+"."+ServiceType+".local", "port", a.info.Port)
	if err := a.rp.Respond(ctx); err != nil && ctx.Err() == nil {
		a.log.Warn("local network announcement stopped", "err", err)
	}
}

// Done is closed once Run has returned and the goodbye has been sent.
func (a *Advertiser) Done() <-chan struct{} { return a.done }

// Rename announces the server under a new name.
func (a *Advertiser) Rename(name string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if instanceName(name) == instanceName(a.info.Name) {
		return
	}
	a.rp.Remove(a.hdl)
	a.info.Name = name
	if err := a.add(); err != nil {
		a.log.Warn("re-announce under new name", "name", name, "err", err)
	}
}

// instanceName makes a DNS-SD instance name: at most 63 bytes, no dots
// (which would read as label separators in some browsers).
func instanceName(name string) string {
	name = strings.TrimSpace(strings.ReplaceAll(name, ".", " "))
	if name == "" {
		name = "WatchMyMedia"
	}
	// Cut to 63 bytes without splitting a character, as auth's clip does.
	if n := 63; len(name) > n {
		for n > 0 && !utf8.RuneStart(name[n]) {
			n--
		}
		name = name[:n]
	}
	return name
}

func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

type logWriter struct{ log *slog.Logger }

func (w logWriter) Write(p []byte) (int, error) {
	w.log.Debug("mdns", "msg", string(bytes.TrimSpace(p)))
	return len(p), nil
}
