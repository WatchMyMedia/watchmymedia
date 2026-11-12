// Package server supervises the bundled server. The controller owns its process;
// the tray only reads snapshots and sends actions, never blocks its message loop.
package server

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Play struct{ User, Title, Mode, State string }
type Status struct {
	Version         string `json:"version"`
	SetupRequired   bool   `json:"setupRequired"`
	SetupIncomplete bool   `json:"setupIncomplete"`
	Sessions        []struct {
		Encoder string `json:"encoder"`
	} `json:"sessions"`
	Plays []Play `json:"plays"`
}
type Snapshot struct {
	State, Error string
	Port         int
	Status       Status
}

func (s Snapshot) Label() string {
	switch s.State {
	case "running":
		return fmt.Sprintf("Server running · port %d", s.Port)
	case "conflict":
		return fmt.Sprintf("Port %d is in use by another app", s.Port)
	case "failed":
		return "Server failed — open logs for details"
	case "stopped":
		return "Server stopped"
	default:
		return "Server starting…"
	}
}
func (s Snapshot) Playing() bool {
	return s.State == "running" && (len(s.Status.Plays) > 0 || len(s.Status.Sessions) > 0)
}

type Config struct {
	Executable, DataDir       string
	Args, Env                 []string // Args is normally ["serve"], overridden by process tests.
	Port                      int
	PollInterval, StopTimeout time.Duration
	OpenSetup                 func(string)
}
type Controller struct {
	cfg     Config
	mu      sync.RWMutex
	state   Snapshot
	actions chan bool // true chooses another port, false restarts the same port
}

func New(cfg Config) *Controller {
	if cfg.Args == nil {
		cfg.Args = []string{"serve"}
	}
	if cfg.PollInterval == 0 {
		cfg.PollInterval = 3 * time.Second
	}
	if cfg.StopTimeout == 0 {
		cfg.StopTimeout = 5 * time.Second
	}
	return &Controller{cfg: cfg, state: Snapshot{State: "starting", Port: cfg.Port}, actions: make(chan bool, 1)}
}
func (c *Controller) Snapshot() Snapshot { c.mu.RLock(); defer c.mu.RUnlock(); return c.state }
func (c *Controller) set(s Snapshot)     { c.mu.Lock(); c.state = s; c.mu.Unlock() }
func (c *Controller) Restart(newPort bool) {
	select {
	case c.actions <- newPort:
	default:
	}
}
func (c *Controller) LogPath() string  { return filepath.Join(c.cfg.DataDir, "Logs", "server.log") }
func URL(port int, path string) string { return "http://127.0.0.1:" + strconv.Itoa(port) + "/" + path }

// Run returns only after the child has exited and its job has been closed.
func (c *Controller) Run(ctx context.Context) {
	port := c.cfg.Port
	delay := time.Second
	for {
		if ctx.Err() != nil {
			c.set(Snapshot{State: "stopped", Port: port})
			return
		}
		retry, next, err := c.once(ctx, port, &delay)
		if next {
			p, e := freePort()
			if e != nil {
				err = e
			} else {
				// Persist only after finding a port. A later launch uses the same one.
				if e = SavePort(c.cfg.DataDir, p); e != nil {
					err = e
				} else {
					port = p
					delay = time.Second
					continue
				}
			}
		}
		if ctx.Err() != nil {
			continue
		}
		if err != nil {
			if f, e := os.OpenFile(c.LogPath(), os.O_APPEND|os.O_WRONLY, 0600); e == nil {
				fmt.Fprintln(f, "tray supervisor:", err)
				f.Close()
			}
			c.set(Snapshot{State: "failed", Error: err.Error(), Port: port})
		}
		if retry { // bounded backoff; explicit restart interrupts the wait
			t := time.NewTimer(delay)
			select {
			case <-ctx.Done():
			case next = <-c.actions:
			case <-t.C:
			}
			t.Stop()
			delay = min(delay*2, 60*time.Second)
		} else {
			select {
			case <-ctx.Done():
			case next = <-c.actions:
			}
		}
		if next {
			p, e := freePort()
			if e == nil {
				e = SavePort(c.cfg.DataDir, p)
			}
			if e == nil {
				port = p
				delay = time.Second
			} else {
				c.set(Snapshot{State: "failed", Error: e.Error(), Port: port})
			}
		}
	}
}
func (c *Controller) once(ctx context.Context, port int, delay *time.Duration) (retry, newPort bool, err error) {
	// Never poll a listener that was already there: it can belong to another
	// signed-in user with another data directory.
	l, e := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if e != nil {
		c.set(Snapshot{State: "conflict", Error: e.Error(), Port: port})
		select {
		case <-ctx.Done():
			return false, false, nil
		case next := <-c.actions:
			return true, next, nil
		}
	}
	_ = l.Close()
	c.set(Snapshot{State: "starting", Port: port})
	if e = os.MkdirAll(filepath.Dir(c.LogPath()), 0700); e != nil {
		return false, false, e
	}
	log, e := newLog(c.LogPath(), 20*1024*1024)
	if e != nil {
		return false, false, e
	}
	defer log.Close()
	env := Environment(c.cfg.Env, c.cfg.DataDir, port, filepath.Dir(c.cfg.Executable))
	p, e := startProcess(c.cfg.Executable, c.cfg.Args, env, c.cfg.DataDir, log)
	if e != nil {
		return true, false, e
	}
	defer p.close()
	tick := time.NewTicker(c.cfg.PollInterval)
	defer tick.Stop()
	client := &http.Client{Timeout: 2 * time.Second, Transport: &http.Transport{Proxy: nil}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	defer client.CloseIdleConnections()
	for {
		select {
		case <-ctx.Done():
			p.stop(c.cfg.StopTimeout)
			return false, false, nil
		case next := <-c.actions:
			p.stop(c.cfg.StopTimeout)
			return true, next, nil
		case e := <-p.done:
			return true, false, fmt.Errorf("server exited: %v", e)
		case <-tick.C:
			s, e := fetchStatus(ctx, client, URL(port, "api/playback/local"))
			// A failed bind can race the preflight check. Do not publish a
			// status after the process has exited.
			select {
			case e := <-p.done:
				return true, false, fmt.Errorf("server exited: %v", e)
			default:
			}
			if e != nil {
				c.set(Snapshot{State: "starting", Port: port})
				continue
			}
			*delay = time.Second
			c.set(Snapshot{State: "running", Port: port, Status: s})
			if s.SetupRequired || s.SetupIncomplete {
				marker := filepath.Join(c.cfg.DataDir, "setup-opened")
				if _, e := os.Stat(marker); os.IsNotExist(e) && c.cfg.OpenSetup != nil {
					if e = os.WriteFile(marker, []byte("1\n"), 0600); e == nil {
						c.cfg.OpenSetup(URL(port, "setup"))
					}
				}
			}
		}
	}
}
func fetchStatus(ctx context.Context, client *http.Client, url string) (Status, error) {
	r, e := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if e != nil {
		return Status{}, e
	}
	res, e := client.Do(r)
	if e != nil {
		return Status{}, e
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return Status{}, fmt.Errorf("local status: HTTP %d", res.StatusCode)
	}
	var s Status
	e = json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&s)
	if e == nil && s.Version == "" {
		e = fmt.Errorf("local status has no version")
	}
	return s, e
}
func freePort() (int, error) {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		return 0, e
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}
func LoadPort(dir string) int {
	b, e := os.ReadFile(filepath.Join(dir, "tray-port"))
	p, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	if e != nil || p < 1 || p > 65535 {
		return 27587
	}
	return p
}
func SavePort(dir string, p int) error {
	if e := os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	return os.WriteFile(filepath.Join(dir, "tray-port"), []byte(strconv.Itoa(p)+"\n"), 0600)
}
func Environment(base []string, dir string, port int, bin string) []string {
	if base == nil {
		base = os.Environ()
	}
	// WATCHMYMEDIA_INSTALL makes Settings' Check for updates offer this app's
	// installer rather than the portable server archive.
	set := map[string]string{"WATCHMYMEDIA_DATA_DIR": dir, "WATCHMYMEDIA_PORT": strconv.Itoa(port), "WATCHMYMEDIA_EXIT_ON_STDIN_EOF": "1", "WATCHMYMEDIA_FFMPEG_PATH": filepath.Join(bin, "ffmpeg.exe"), "WATCHMYMEDIA_FFPROBE_PATH": filepath.Join(bin, "ffprobe.exe"), "WATCHMYMEDIA_INSTALL": "windows-app"}
	var out []string
	for _, v := range base {
		k, _, _ := strings.Cut(v, "=")
		if _, ok := set[strings.ToUpper(k)]; !ok {
			out = append(out, v)
		}
	}
	for k, v := range set {
		out = append(out, k+"="+v)
	}
	return out
}
