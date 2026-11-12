package plex

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
)

func TestOwnerDeviceCredentialSelectsExactServer(t *testing.T) {
	const cloudToken = "synthetic-cloud-jwt"
	const deviceToken = "synthetic-owner-device-secret"
	calls := 0
	client := pmsClient(t, cloudToken, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Method != http.MethodGet || r.URL.Path != "/api/v2/devices" || r.URL.RawQuery != "" || r.Header.Get("X-Plex-Token") != cloudToken || r.Header.Get("Accept") != "application/json" {
			t.Error("device inventory request had wrong method, path, query, or headers")
		}
		pmsJSON(w, `[{"clientIdentifier":"other-server","product":"Plex Media Server","token":"synthetic-other-secret"},{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":"synthetic-owner-device-secret"},{"clientIdentifier":"phone-client","product":"Plex for Android","token":"synthetic-phone-secret"}]`)
	})
	credential, err := client.OwnerDeviceCredential(t.Context(), Account{ID: 701}, Resource{
		MachineIdentifier: "synthetic-plex-server", Owned: true,
		Credential: Credential{token: "synthetic-rejected-resource-jwt"},
	})
	if err != nil || credential.Token() != deviceToken || calls != 1 {
		t.Fatalf("exact owner device selection failed (calls=%d): %v", calls, err)
	}
	encoded, err := json.Marshal(credential)
	if err != nil {
		t.Fatal(err)
	}
	for _, rendered := range []string{fmt.Sprint(credential), fmt.Sprintf("%+v", credential), fmt.Sprintf("%#v", credential), string(encoded)} {
		if strings.Contains(rendered, deviceToken) || !strings.Contains(rendered, "redacted") {
			t.Error("owner device credential was not redacted")
		}
	}
}

func TestOwnerDeviceCredentialRejectsUnprovenOwnerBeforeRequest(t *testing.T) {
	for _, tc := range []struct {
		name     string
		token    string
		owner    Account
		resource Resource
		kind     string
	}{
		{"missing owner", "synthetic-cloud-jwt", Account{}, Resource{MachineIdentifier: "synthetic-plex-server", Owned: true}, "invalid_identity"},
		{"negative owner", "synthetic-cloud-jwt", Account{ID: -1}, Resource{MachineIdentifier: "synthetic-plex-server", Owned: true}, "invalid_identity"},
		{"restricted profile", "synthetic-cloud-jwt", Account{ID: 83, Restricted: true}, Resource{MachineIdentifier: "synthetic-plex-server", Owned: true}, "invalid_identity"},
		{"shared server", "synthetic-cloud-jwt", Account{ID: 72}, Resource{MachineIdentifier: "synthetic-plex-server"}, "invalid_identity"},
		{"missing machine", "synthetic-cloud-jwt", Account{ID: 701}, Resource{Owned: true}, "invalid_identity"},
		{"invalid machine", "synthetic-cloud-jwt", Account{ID: 701}, Resource{MachineIdentifier: "../server", Owned: true}, "invalid_identity"},
		{"missing credential", "", Account{ID: 701}, Resource{MachineIdentifier: "synthetic-plex-server", Owned: true}, "invalid_credentials"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := pmsClient(t, tc.token, func(w http.ResponseWriter, _ *http.Request) {
				calls++
				pmsJSON(w, `[]`)
			})
			credential, err := client.OwnerDeviceCredential(t.Context(), tc.owner, tc.resource)
			pmsErrorKind(t, err, tc.kind)
			if credential.Token() != "" || calls != 0 {
				t.Fatal("invalid owner proof produced a credential or request")
			}
		})
	}
}

func TestOwnerDeviceCredentialFailsClosed(t *testing.T) {
	for _, tc := range []struct {
		name string
		body string
		kind string
	}{
		{"empty", `[]`, "owner_device_access_unavailable"},
		{"null", `null`, "owner_device_access_unavailable"},
		{"wrong envelope", `{"Device":[{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":"synthetic-device-secret"}]}`, "invalid_response"},
		{"malformed", `[{"token":"synthetic-device-secret"`, "invalid_response"},
		{"wrong server", `[{"clientIdentifier":"other-server","product":"Plex Media Server","token":"synthetic-device-secret"}]`, "owner_device_access_unavailable"},
		{"missing machine", `[{"product":"Plex Media Server","token":"synthetic-device-secret"}]`, "owner_device_access_unavailable"},
		{"missing product", `[{"clientIdentifier":"synthetic-plex-server","token":"synthetic-device-secret"}]`, "owner_device_access_unavailable"},
		{"wrong product", `[{"clientIdentifier":"synthetic-plex-server","product":"Plex for Android","token":"synthetic-device-secret"}]`, "owner_device_access_unavailable"},
		{"missing token", `[{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server"}]`, "owner_device_access_unavailable"},
		{"empty token", `[{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":""}]`, "owner_device_access_unavailable"},
		{"whitespace token", `[{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":" "}]`, "owner_device_access_unavailable"},
		{"invalid token header", `[{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":"synthetic-device-secret\r\n"}]`, "owner_device_access_unavailable"},
		{"duplicate exact match", `[{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":"synthetic-device-secret"},{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":"synthetic-other-secret"}]`, "owner_device_access_unavailable"},
		{"duplicate machine wrong product", `[{"clientIdentifier":"synthetic-plex-server","product":"Plex Media Server","token":"synthetic-device-secret"},{"clientIdentifier":"synthetic-plex-server","product":"Plex for Android","token":"synthetic-other-secret"}]`, "owner_device_access_unavailable"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := pmsClient(t, "synthetic-cloud-secret", func(w http.ResponseWriter, _ *http.Request) {
				pmsJSON(w, tc.body)
			})
			credential, err := client.OwnerDeviceCredential(t.Context(), Account{ID: 701}, Resource{
				MachineIdentifier: "synthetic-plex-server", Owned: true,
				Credential: Credential{token: "synthetic-resource-secret"},
			})
			pmsErrorKind(t, err, tc.kind)
			if credential.Token() != "" {
				t.Fatal("failed selection returned a fallback credential")
			}
			if strings.Contains(fmt.Sprint(err), "synthetic-") {
				t.Fatal("selection error exposed response or credential data")
			}
		})
	}
}

func TestOwnerDeviceCredentialPreservesSafeTransportFailure(t *testing.T) {
	client := pmsClient(t, "synthetic-cloud-secret", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		pmsJSON(w, `{"error":"synthetic-device-secret"}`)
	})
	credential, err := client.OwnerDeviceCredential(t.Context(), Account{ID: 701}, Resource{MachineIdentifier: "synthetic-plex-server", Owned: true})
	pmsErrorKind(t, err, "authorization")
	if credential.Token() != "" || strings.Contains(fmt.Sprint(err), "synthetic-") {
		t.Fatal("failed inventory request returned or exposed a credential")
	}
}
