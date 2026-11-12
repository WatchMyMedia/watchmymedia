package plex

import (
	"context"
	"strings"
)

// OwnerDeviceCredential reads clients.plex.tv's device inventory to obtain the
// selected owner's PMS device credential. It is an explicit owner-only bridge
// for servers that reject a cloud/resource JWT, never a shared/Home user token
// fallback. The /api/v2/devices response has no ownership field.
//
// Before calling, the caller MUST verify owner with Account and select the owned
// resource from Resources using the SAME credential as this client. Structs
// alone do not prove that relationship. Never pass a switched Home profile or a
// shared-user identity. A new PMS client using the returned credential must also
// verify its machine identity against resource.MachineIdentifier before reads.
//
// Only one exact machine match with product "Plex Media Server" and a nonempty
// token is accepted. No match, duplicate match, or missing token is an error;
// neither the input cloud credential nor another device token is substituted.
func (c *Client) OwnerDeviceCredential(ctx context.Context, owner Account, resource Resource) (Credential, error) {
	if owner.ID <= 0 || owner.Restricted || !resource.Owned || !validMachineIdentifier(resource.MachineIdentifier) {
		return Credential{}, &Error{Kind: "invalid_identity"}
	}
	if c.token == "" {
		return Credential{}, &Error{Kind: "invalid_credentials"}
	}
	var devices []struct {
		ClientIdentifier string `json:"clientIdentifier"`
		Product          string `json:"product"`
		Token            string `json:"token"`
	}
	if err := c.get(ctx, "/api/v2/devices", nil, &devices); err != nil {
		return Credential{}, err
	}
	var token, product string
	matches := 0
	for _, device := range devices {
		if device.ClientIdentifier == resource.MachineIdentifier {
			matches++
			token, product = device.Token, device.Product
		}
	}
	if matches != 1 || product != "Plex Media Server" || strings.TrimSpace(token) == "" || strings.ContainsAny(token, "\r\n") {
		return Credential{}, &Error{Kind: "owner_device_access_unavailable"}
	}
	return Credential{token: token}, nil
}
