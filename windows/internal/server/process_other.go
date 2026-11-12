//go:build !windows

// The non-Windows implementation lets the supervisor's process and HTTP tests
// run on the development Mac. The desktop application itself is Windows only.
package server

func (p *process) launch() error {
	if e := p.cmd.Start(); e != nil {
		return e
	}
	p.kill = func() { _ = p.cmd.Process.Kill() }
	p.release = func() {}
	return nil
}
