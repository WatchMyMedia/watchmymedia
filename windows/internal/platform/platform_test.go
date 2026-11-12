package platform

import "testing"

func TestDesktopSupportBoundaries(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name string
		h    Host
		want bool
	}{
		{"Windows 10", Host{Major: 10, Build: 19045, ProductType: 1, NativeMachine: amd64}, false},
		{"Windows 11 24H2", Host{Major: 10, Build: 26100, ProductType: 1, NativeMachine: amd64}, false},
		{"one build before", Host{Major: 10, Build: 26199, ProductType: 1, NativeMachine: amd64}, false},
		{"25H2", Host{Major: 10, Build: 26200, ProductType: 1, NativeMachine: amd64}, true},
		{"26H2", Host{Major: 10, Build: 26300, ProductType: 1, NativeMachine: amd64}, true},
		{"later major", Host{Major: 11, ProductType: 1, NativeMachine: amd64}, true},
		{"Server 2025", Host{Major: 10, Build: 26100, ProductType: 3, NativeMachine: amd64}, false},
		{"future Server", Host{Major: 10, Build: 28000, ProductType: 3, NativeMachine: amd64}, false},
		{"domain controller", Host{Major: 10, Build: 28000, ProductType: 2, NativeMachine: amd64}, false},
		{"ARM64 x64 emulation", Host{Major: 10, Build: 28000, ProductType: 1, NativeMachine: 0xaa64}, false},
		{"x86", Host{Major: 10, Build: 26200, ProductType: 1, NativeMachine: 0x14c}, false},
		{"unknown system", Host{}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := tc.h.DesktopSupported(); got != tc.want {
				t.Fatalf("supported=%t, want %t", got, tc.want)
			}
			// Hosted-runner environment alone never relaxes the normal binary.
			if got := allowed(tc.h, false, hosted); got != tc.want {
				t.Fatalf("production allowed=%t, want %t", got, tc.want)
			}
		})
	}
}

func hosted(key string) string {
	return map[string]string{"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Windows"}[key]
}

func TestCIExceptionIsRestrictedToHostedServerBuilds(t *testing.T) {
	t.Parallel()
	server := Host{Major: 10, Build: 26100, ProductType: 3, NativeMachine: amd64}
	if !allowed(server, true, hosted) {
		t.Fatal("dedicated hosted Server build was refused")
	}
	for _, key := range []string{"GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS"} {
		getenv := func(k string) string {
			if k == key {
				return ""
			}
			return hosted(k)
		}
		if allowed(server, true, getenv) {
			t.Fatalf("allowed missing %s", key)
		}
	}
	for _, h := range []Host{
		{Major: 10, Build: 26100, ProductType: 1, NativeMachine: amd64},
		{Major: 10, Build: 28000, ProductType: 3, NativeMachine: 0xaa64},
		{},
	} {
		if allowed(h, true, hosted) {
			t.Fatalf("CI admitted unsupported client/architecture: %+v", h)
		}
	}
}
