package server

import (
	"io"
	"os/exec"
	"time"
)

type process struct {
	cmd     *exec.Cmd
	stdin   io.WriteCloser
	done    chan error
	kill    func()
	release func()
}

func startProcess(exe string, args, env []string, dir string, log io.Writer) (*process, error) {
	cmd := exec.Command(exe, args...)
	cmd.Env = env
	cmd.Dir = dir
	cmd.Stdout = log
	cmd.Stderr = log
	stdin, e := cmd.StdinPipe()
	if e != nil {
		return nil, e
	}
	p := &process{cmd: cmd, stdin: stdin, done: make(chan error, 1)}
	if e = p.launch(); e != nil {
		_ = stdin.Close()
		return nil, e
	}
	go func() { p.done <- cmd.Wait() }()
	return p, nil
}
func (p *process) stop(timeout time.Duration) {
	_ = p.stdin.Close()
	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case <-p.done:
	case <-t.C:
		p.kill()
		<-p.done
	}
}
func (p *process) close() { _ = p.stdin.Close(); p.release() }
