package seerr

import (
	"net/url"
	"testing"
)

// Seerr refuses a search whose query has + for spaces or other reserved
// characters left unescaped.
func TestEncodeEscapesForSeerr(t *testing.T) {
	got := encode(url.Values{"query": {"don't stop & go+"}, "page": {"1"}})
	if want := "page=1&query=don%27t%20stop%20%26%20go%2B"; got != want {
		t.Errorf("encode = %q, want %q", got, want)
	}
}

func TestPermissions(t *testing.T) {
	admin := User{Permissions: PermAdmin}
	member := User{Permissions: PermRequestMovie}
	approved := User{Permissions: PermRequest | PermAutoApproveTV}
	if !admin.CanRequest("tv") || !admin.AutoApproved("movie") {
		t.Error("an admin can't do everything")
	}
	if !member.CanRequest("movie") || member.CanRequest("tv") || member.AutoApproved("movie") {
		t.Errorf("member = %+v", member)
	}
	if !approved.AutoApproved("tv") || approved.AutoApproved("movie") {
		t.Errorf("approved = %+v", approved)
	}
}
