package plex

import (
	"net/http"
	"testing"
)

func TestHistoryAccountsJSONAndXML(t *testing.T) {
	for _, tc := range []struct {
		name string
		body string
	}{
		{"JSON numeric and string IDs", `{"MediaContainer":{"size":4,"Account":[{"id":0,"key":"/accounts/0","name":""},{"id":1,"key":"/accounts/1","name":"Same Fictional Name"},{"id":"72","key":"/accounts/72","name":"Same Fictional Name"},{"id":83,"key":"/accounts/83","name":"Unrelated Fictional Name"}]}}`},
		{"JSON string placeholder", `{"MediaContainer":{"size":4,"Account":[{"id":"0","key":"/accounts/0"},{"id":1,"key":"/accounts/1"},{"id":72,"key":"/accounts/72"},{"id":83,"key":"/accounts/83"}]}}`},
		{"XML", `<MediaContainer size="4"><Account id="0" key="/accounts/0" name=""/><Account id="1" key="/accounts/1" name="Same Fictional Name"/><Account id="72" key="/accounts/72" name="Same Fictional Name"/><Account id="83" key="/accounts/83" name="Unrelated Fictional Name"/></MediaContainer>`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := pmsClient(t, "synthetic-owner-token", func(w http.ResponseWriter, r *http.Request) {
				calls++
				if r.URL.Path != "/accounts" || r.Header.Get("X-Plex-Token") != "synthetic-owner-token" || r.URL.RawQuery != "" {
					t.Error("inventory read used wrong path, credential or query")
				}
				pmsJSON(w, tc.body)
			})
			inventory, err := client.HistoryAccounts(t.Context())
			if err != nil || len(inventory) != 4 || calls != 1 {
				t.Fatalf("inventory len=%d calls=%d err=%v", len(inventory), calls, err)
			}
			for index, id := range []PMSAccountID{0, 1, 72, 83} {
				if inventory[index].ID != id {
					t.Error("inventory ID changed during decoding")
				}
			}
			owner := Account{ID: 701}
			resource := Resource{MachineIdentifier: "synthetic-plex-server", Owned: true}
			for _, id := range []int64{701, 72, 83} {
				resolved, err := ResolveHistoryIdentity(owner, resource, Account{ID: id}, inventory)
				if err != nil || resolved.CloudID != CloudAccountID(id) {
					t.Fatalf("name-independent binding failed: %v", err)
				}
			}
			identity, err := ResolveHistoryIdentity(owner, resource, Account{ID: 0}, inventory)
			pmsErrorKind(t, err, "invalid_identity")
			if identity != (HistoryIdentity{}) {
				t.Fatal("placeholder bound to a cloud/Home identity")
			}
		})
	}
}

func TestHistoryAccountsRejectsMalformedInventory(t *testing.T) {
	for _, tc := range []struct {
		name string
		body string
	}{
		{"missing envelope", `{"Account":[{"id":1,"key":"/accounts/1"}]}`},
		{"null envelope", `{"MediaContainer":null}`},
		{"wrong XML envelope", `<Accounts size="1"><Account id="1" key="/accounts/1"/></Accounts>`},
		{"missing size", `{"MediaContainer":{"Account":[{"id":1,"key":"/accounts/1"}]}}`},
		{"wrong size", `{"MediaContainer":{"size":2,"Account":[{"id":1,"key":"/accounts/1"}]}}`},
		{"missing rows", `{"MediaContainer":{"size":1}}`},
		{"missing ID", `{"MediaContainer":{"size":1,"Account":[{"key":"/accounts/1"}]}}`},
		{"missing key", `{"MediaContainer":{"size":1,"Account":[{"id":1}]}}`},
		{"mismatched key", `{"MediaContainer":{"size":1,"Account":[{"id":72,"key":"/accounts/1"}]}}`},
		{"absolute key", `{"MediaContainer":{"size":1,"Account":[{"id":72,"key":"https://example.invalid/accounts/72"}]}}`},
		{"duplicate ID", `{"MediaContainer":{"size":2,"Account":[{"id":72,"key":"/accounts/72"},{"id":"72","key":"/accounts/72"}]}}`},
		{"duplicate placeholder", `{"MediaContainer":{"size":2,"Account":[{"id":0,"key":"/accounts/0"},{"id":"0","key":"/accounts/0"}]}}`},
		{"duplicate XML placeholder", `<MediaContainer size="2"><Account id="0" key="/accounts/0"/><Account id="0" key="/accounts/0"/></MediaContainer>`},
		{"placeholder key mismatch", `{"MediaContainer":{"size":1,"Account":[{"id":0,"key":"/accounts/1"}]}}`},
		{"placeholder noncanonical key", `<MediaContainer size="1"><Account id="0" key="/accounts/00"/></MediaContainer>`},
		{"noncanonical placeholder", `{"MediaContainer":{"size":1,"Account":[{"id":"00","key":"/accounts/0"}]}}`},
		{"negative zero", `{"MediaContainer":{"size":1,"Account":[{"id":-0,"key":"/accounts/0"}]}}`},
		{"exponent zero", `{"MediaContainer":{"size":1,"Account":[{"id":0e0,"key":"/accounts/0"}]}}`},
		{"XML noncanonical placeholder", `<MediaContainer size="1"><Account id="00" key="/accounts/0"/></MediaContainer>`},
		{"negative JSON ID", `{"MediaContainer":{"size":1,"Account":[{"id":-1,"key":"/accounts/-1"}]}}`},
		{"negative ID", `<MediaContainer size="1"><Account id="-1" key="/accounts/-1"/></MediaContainer>`},
		{"invalid XML ID", `<MediaContainer size="1"><Account id="72x" key="/accounts/72x"/></MediaContainer>`},
		{"lossy numeric ID", `{"MediaContainer":{"size":1,"Account":[{"id":7.2e1,"key":"/accounts/72"}]}}`},
		{"overflow ID", `{"MediaContainer":{"size":1,"Account":[{"id":9223372036854775808,"key":"/accounts/9223372036854775808"}]}}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := pmsClient(t, "synthetic-owner-token", func(w http.ResponseWriter, _ *http.Request) {
				pmsJSON(w, tc.body)
			})
			inventory, err := client.HistoryAccounts(t.Context())
			pmsErrorKind(t, err, "invalid_response")
			if len(inventory) != 0 {
				t.Fatal("invalid inventory returned partial identities")
			}
		})
	}
}

func TestResolveHistoryIdentityKeepsNamespaces(t *testing.T) {
	owner := Account{ID: 701, HomeAdmin: true}
	resource := Resource{MachineIdentifier: "synthetic-plex-server", Owned: true}
	inventory := []HistoryAccount{{ID: 0, Key: "/accounts/0"}, {ID: 1, Key: "/accounts/1"}, {ID: 72, Key: "/accounts/72"}, {ID: 83, Key: "/accounts/83"}}
	for _, tc := range []struct {
		name     string
		subject  Account
		localID  PMSAccountID
		evidence string
	}{
		{"owner", owner, 1, HistoryOwnerRuleEvidence},
		{"shared user", Account{ID: 72}, 72, HistoryUserRuleEvidence},
		{"managed Home user", Account{ID: 83, Restricted: true}, 83, HistoryUserRuleEvidence},
	} {
		t.Run(tc.name, func(t *testing.T) {
			identity, err := ResolveHistoryIdentity(owner, resource, tc.subject, inventory)
			if err != nil || identity.CloudID != CloudAccountID(tc.subject.ID) || identity.LocalID != tc.localID || identity.MachineIdentifier != resource.MachineIdentifier || identity.Evidence != tc.evidence {
				t.Fatalf("identity resolution failed: %+v %v", identity, err)
			}
		})
	}
}

func TestResolveHistoryIdentityFailsClosed(t *testing.T) {
	owner := Account{ID: 701}
	resource := Resource{MachineIdentifier: "synthetic-plex-server", Owned: true}
	inventory := []HistoryAccount{{ID: 1, Key: "/accounts/1"}, {ID: 72, Key: "/accounts/72"}}
	for _, tc := range []struct {
		name      string
		owner     Account
		resource  Resource
		subject   Account
		inventory []HistoryAccount
		kind      string
	}{
		{"missing owner", Account{}, resource, Account{ID: 72}, inventory, "invalid_identity"},
		{"missing subject", owner, resource, Account{}, inventory, "invalid_identity"},
		{"not owned", owner, Resource{MachineIdentifier: "synthetic-plex-server"}, Account{ID: 72}, inventory, "invalid_identity"},
		{"missing machine", owner, Resource{Owned: true}, Account{ID: 72}, inventory, "invalid_identity"},
		{"invalid machine", owner, Resource{MachineIdentifier: "../different", Owned: true}, Account{ID: 72}, inventory, "invalid_identity"},
		{"nonowner reserved ID", owner, resource, Account{ID: 1}, inventory, "invalid_identity"},
		{"missing member", owner, resource, Account{ID: 83}, inventory, "history_account_unavailable"},
		{"empty inventory", owner, resource, owner, nil, "history_account_unavailable"},
		{"no owner fallback", owner, resource, owner, []HistoryAccount{{ID: 701, Key: "/accounts/701"}}, "history_account_unavailable"},
		{"duplicate inventory", owner, resource, Account{ID: 72}, append(append([]HistoryAccount{}, inventory...), HistoryAccount{ID: 72, Key: "/accounts/72"}), "invalid_history_inventory"},
		{"duplicate placeholders", owner, resource, owner, []HistoryAccount{{ID: 0, Key: "/accounts/0"}, {ID: 0, Key: "/accounts/0"}, {ID: 1, Key: "/accounts/1"}}, "invalid_history_inventory"},
		{"placeholder cannot replace owner", owner, resource, owner, []HistoryAccount{{ID: 0, Key: "/accounts/0"}}, "history_account_unavailable"},
		{"invalid inventory", owner, resource, Account{ID: 72}, []HistoryAccount{{ID: -1, Key: "/accounts/-1"}, {ID: 72, Key: "/accounts/72"}}, "invalid_history_inventory"},
		{"wrong inventory key", owner, resource, Account{ID: 72}, []HistoryAccount{{ID: 72, Key: "/accounts/1"}}, "invalid_history_inventory"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			identity, err := ResolveHistoryIdentity(tc.owner, tc.resource, tc.subject, tc.inventory)
			pmsErrorKind(t, err, tc.kind)
			if identity != (HistoryIdentity{}) {
				t.Fatal("failed binding returned an identity")
			}
		})
	}
}
