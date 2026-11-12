package server

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

// The helper is a real child process on both platforms. Windows uses the same
// suspended launch/job-object path as the bundled server.
func TestHelperProcess(t *testing.T) {
	mode := os.Getenv("WMM_TRAY_TEST_HELPER")
	if mode == "" {
		return
	}
	if mode == "stuck" {
		time.Sleep(time.Hour)
	}
	if mode == "exit" {
		os.Exit(17)
	}
	port := os.Getenv("WATCHMYMEDIA_PORT")
	l, e := net.Listen("tcp", "127.0.0.1:"+port)
	if e != nil {
		os.Exit(18)
	}
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/crash" {
			os.Exit(17)
		}
		fmt.Fprintf(w, `{"version":%q,"setupIncomplete":true,"sessions":[],"plays":[{"user":"Viewer","title":"Harbor Lights","mode":"direct"}]}`, strconv.Itoa(os.Getpid()))
	})
	go http.Serve(l, h)
	fmt.Println("server started")
	_, _ = io.Copy(io.Discard, os.Stdin)
	_ = l.Close()
	os.Exit(0)
}
func testConfig(t *testing.T, mode string) Config {
	t.Helper()
	p, e := freePort()
	if e != nil {
		t.Fatal(e)
	}
	exe, e := os.Executable()
	if e != nil {
		t.Fatal(e)
	}
	return Config{Executable: exe, Args: []string{"-test.run=^TestHelperProcess$"}, Env: append(os.Environ(), "WMM_TRAY_TEST_HELPER="+mode), DataDir: t.TempDir(), Port: p, PollInterval: 10 * time.Millisecond, StopTimeout: 200 * time.Millisecond}
}
func waitState(t *testing.T, c *Controller, predicate func(Snapshot) bool) Snapshot {
	t.Helper()
	deadline := time.NewTimer(10 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(10 * time.Millisecond)
	defer tick.Stop()
	for {
		s := c.Snapshot()
		if predicate(s) {
			return s
		}
		select {
		case <-deadline.C:
			t.Fatalf("waiting for state, got %+v", s)
		case <-tick.C:
		}
	}
}
func runController(t *testing.T, c *Controller) (context.CancelFunc, <-chan struct{}) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); c.Run(ctx) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(10 * time.Second):
			t.Error("controller did not stop")
		}
	})
	return cancel, done
}
func TestRunRestartCrashAndShutdown(t *testing.T) {
	cfg := testConfig(t, "serve")
	opened := make(chan string, 3)
	cfg.OpenSetup = func(u string) { opened <- u }
	c := New(cfg)
	cancel, done := runController(t, c)
	first := waitState(t, c, func(s Snapshot) bool { return s.State == "running" })
	if !first.Playing() {
		t.Fatal("direct play must keep the PC awake")
	}
	select {
	case u := <-opened:
		if u != URL(cfg.Port, "setup") {
			t.Fatal(u)
		}
	case <-time.After(time.Second):
		t.Fatal("setup not opened")
	}
	c.Restart(false)
	second := waitState(t, c, func(s Snapshot) bool { return s.State == "running" && s.Status.Version != first.Status.Version })
	res, e := http.Get(URL(cfg.Port, "crash"))
	if e == nil {
		res.Body.Close()
	}
	waitState(t, c, func(s Snapshot) bool { return s.State == "running" && s.Status.Version != second.Status.Version })
	select {
	case <-opened:
		t.Fatal("setup opened more than once")
	default:
	}
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("shutdown timed out")
	}
	if c.Snapshot().State != "stopped" {
		t.Fatal(c.Snapshot())
	}
	l, e := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(cfg.Port)))
	if e != nil {
		t.Fatalf("child still listening: %v", e)
	}
	l.Close()
}
func TestPortConflictDoesNotReadOtherServer(t *testing.T) {
	reads := make(chan struct{}, 1)
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case reads <- struct{}{}:
		default:
		}
		fmt.Fprint(w, `{"version":"another-user"}`)
	}))
	defer other.Close()
	cfg := testConfig(t, "serve")
	cfg.Port = other.Listener.Addr().(*net.TCPAddr).Port
	c := New(cfg)
	runController(t, c)
	waitState(t, c, func(s Snapshot) bool { return s.State == "conflict" })
	c.Restart(true)
	s := waitState(t, c, func(s Snapshot) bool { return s.State == "running" })
	if s.Port == cfg.Port || LoadPort(cfg.DataDir) != s.Port {
		t.Fatal("alternate port not saved", s)
	}
	select {
	case <-reads:
		t.Fatal("read another user's server")
	default:
	}
}
func TestHungChildIsTerminated(t *testing.T) {
	cfg := testConfig(t, "stuck")
	cfg.StopTimeout = 40 * time.Millisecond
	log, e := newLog(cfg.DataDir+"/test.log", 1024)
	if e != nil {
		t.Fatal(e)
	}
	defer log.Close()
	p, e := startProcess(cfg.Executable, cfg.Args, Environment(cfg.Env, cfg.DataDir, cfg.Port, "."), cfg.DataDir, log)
	if e != nil {
		t.Fatal(e)
	}
	defer p.close()
	done := make(chan struct{})
	go func() { p.stop(cfg.StopTimeout); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("hung child was not killed")
	}
}
func TestLocalStatusFailures(t *testing.T) {
	for _, body := range []string{`{}`, `garbage`, strings.Repeat("x", (1<<20)+1)} {
		t.Run(strconv.Itoa(len(body)), func(t *testing.T) {
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, body) }))
			defer s.Close()
			if _, e := fetchStatus(context.Background(), s.Client(), s.URL); e == nil {
				t.Fatal("accepted invalid status")
			}
		})
	}
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(403) }))
	defer s.Close()
	if _, e := fetchStatus(context.Background(), s.Client(), s.URL); e == nil {
		t.Fatal("accepted forbidden status")
	}
}
func TestEnvironmentAndLogRotation(t *testing.T) {
	dir := t.TempDir()
	env := Environment([]string{"watchmymedia_data_dir=wrong", "WATCHMYMEDIA_PORT=1", "WATCHMYMEDIA_INSTALL=binary", "KEEP=yes"}, dir, 27677, dir)
	joined := strings.Join(env, "\n")
	if strings.Contains(joined, "wrong") || !strings.Contains(joined, "WATCHMYMEDIA_PORT=27677") || !strings.Contains(joined, "KEEP=yes") ||
		strings.Contains(joined, "INSTALL=binary") || !strings.Contains(joined, "WATCHMYMEDIA_INSTALL=windows-app") {
		t.Fatal(env)
	}
	l, e := newLog(dir+"/log", 3)
	if e != nil {
		t.Fatal(e)
	}
	for _, s := range []string{"first", "next"} {
		if _, e = l.Write([]byte(s)); e != nil {
			t.Fatal(e)
		}
	}
	l.Close()
	for name, want := range map[string]string{"log": "next", "log.1": "first"} {
		b, e := os.ReadFile(dir + "/" + name)
		if e != nil || string(b) != want {
			t.Fatalf("%s: %q %v", name, b, e)
		}
	}
}
