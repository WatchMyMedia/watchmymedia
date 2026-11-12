package plex

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"net/http"
	"strconv"
)

// CloudAccountID and PMSAccountID are separate namespaces even when a selected
// non-owner's numeric values coincide. Never use a display name as a crosswalk.
type CloudAccountID int64
type PMSAccountID int64

// HistoryAccount is an entry in a selected PMS's /accounts inventory. Names and
// emails are deliberately omitted: they are neither identifiers nor evidence.
// PMS may include the canonical account 0 placeholder; it is retained for an
// exact inventory count but can never be bound to a cloud/Home identity.
type HistoryAccount struct {
	ID  PMSAccountID
	Key string
}

// HistoryAccounts reads the selected PMS's local history-account inventory.
// The caller must first verify this client's server identity and authenticate an
// owner read. The maintained-client endpoint is not itself a cloud-ID crosswalk.
// A canonical account 0 placeholder is preserved but is never bindable.
func (c *Client) HistoryAccounts(ctx context.Context) ([]HistoryAccount, error) {
	data, err := c.do(ctx, http.MethodGet, "/accounts", nil, nil)
	if err != nil {
		return nil, err
	}
	var container struct {
		XMLName xml.Name `xml:"MediaContainer"`
		Size    *int     `xml:"size,attr" json:"size"`
		Account []struct {
			ID  historyAccountWireID `xml:"id,attr" json:"id"`
			Key string               `xml:"key,attr" json:"key"`
		} `xml:"Account" json:"Account"`
	}
	if err := decodeLegacyContainer(data, &container); err != nil {
		return nil, err
	}
	if container.Size == nil || *container.Size != len(container.Account) {
		return nil, &Error{Kind: "invalid_response"}
	}
	accounts := make([]HistoryAccount, 0, len(container.Account))
	for _, row := range container.Account {
		if !validHistoryAccountWireID(string(row.ID)) {
			return nil, &Error{Kind: "invalid_response"}
		}
		id, err := strconv.ParseInt(string(row.ID), 10, 64)
		if err != nil {
			return nil, &Error{Kind: "invalid_response"}
		}
		accounts = append(accounts, HistoryAccount{ID: PMSAccountID(id), Key: row.Key})
	}
	if !validHistoryInventory(accounts) {
		return nil, &Error{Kind: "invalid_response"}
	}
	return accounts, nil
}

const (
	// These classifications identify the evidence used, not proof that a person
	// independently recognized the returned playback history as their own.
	HistoryOwnerRuleEvidence = "maintained_owner_rule_and_pms_inventory"
	HistoryUserRuleEvidence  = "maintained_user_rule_and_pms_inventory"
)

// HistoryIdentity retains both identity namespaces and the selected server.
// Evidence is intentionally weaker than independent user-history corroboration.
type HistoryIdentity struct {
	CloudID           CloudAccountID
	LocalID           PMSAccountID
	MachineIdentifier string
	Evidence          string
}

// ResolveHistoryIdentity applies the maintained-client identity rule only after
// the caller has established all of these facts:
//   - owner came from Account and resource came from Resources under the SAME
//     verified owner credential; resource belongs to that owner;
//   - subject is an explicitly verified cloud/Home identity, not a name match;
//   - inventory came from HistoryAccounts on the PMS whose identity was verified
//     to match resource.MachineIdentifier.
//
// The pure resolver cannot authenticate those inputs or turn structs into proof.
// It rejects missing/ambiguous inventory membership and never falls back to an
// owner ID or another local account. An owner maps to local 1; a non-owner maps to
// its exact positive cloud/Home ID, except reserved local 1. This classification
// still does not independently corroborate a user's historical playback.
func ResolveHistoryIdentity(owner Account, resource Resource, subject Account, inventory []HistoryAccount) (HistoryIdentity, error) {
	if owner.ID <= 0 || subject.ID <= 0 || !resource.Owned || !validMachineIdentifier(resource.MachineIdentifier) {
		return HistoryIdentity{}, &Error{Kind: "invalid_identity"}
	}
	if !validHistoryInventory(inventory) {
		return HistoryIdentity{}, &Error{Kind: "invalid_history_inventory"}
	}
	localID := PMSAccountID(subject.ID)
	evidence := HistoryUserRuleEvidence
	if subject.ID == owner.ID {
		localID, evidence = 1, HistoryOwnerRuleEvidence
	} else if localID == 1 {
		return HistoryIdentity{}, &Error{Kind: "invalid_identity"}
	}
	for _, account := range inventory {
		if account.ID == localID {
			return HistoryIdentity{
				CloudID: CloudAccountID(subject.ID), LocalID: localID,
				MachineIdentifier: resource.MachineIdentifier, Evidence: evidence,
			}, nil
		}
	}
	return HistoryIdentity{}, &Error{Kind: "history_account_unavailable"}
}

func validHistoryInventory(accounts []HistoryAccount) bool {
	seen := make(map[PMSAccountID]bool, len(accounts))
	for _, account := range accounts {
		if account.ID < 0 || seen[account.ID] || account.Key != "/accounts/"+strconv.FormatInt(int64(account.ID), 10) {
			return false
		}
		seen[account.ID] = true
	}
	return true
}

// Unlike metadata IDs, the PMS account inventory has an observed account 0
// placeholder. Accept only its exact decimal spelling, never a missing ID,
// negative zero, a float/exponent, or another noncanonical numeric encoding.
type historyAccountWireID string

func (id *historyAccountWireID) UnmarshalJSON(data []byte) error {
	value := string(data)
	if len(data) > 0 && data[0] == '"' {
		if err := json.Unmarshal(data, &value); err != nil {
			return &Error{Kind: "invalid_response"}
		}
	}
	if !validHistoryAccountWireID(value) {
		return &Error{Kind: "invalid_response"}
	}
	*id = historyAccountWireID(value)
	return nil
}

func validHistoryAccountWireID(value string) bool {
	return value == "0" || positiveID(value)
}
