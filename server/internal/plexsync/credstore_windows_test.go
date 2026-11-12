//go:build windows

package plexsync

import (
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

// The folder and each grant have a protected access list whose every entry
// is the account the server runs as. Windows splits the folder's
// inheritable entry in two (one for the folder, one inherited by the
// files in it), so the entries are counted only to be at least one.
func TestCredStoreAccessList(t *testing.T) {
	s, err := openCredStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ref, err := s.put(testGrant(t))
	if err != nil {
		t.Fatal(err)
	}
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{s.dir, s.path(ref)} {
		sd, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
		if err != nil {
			t.Fatal(err)
		}
		control, _, err := sd.Control()
		if err != nil || control&windows.SE_DACL_PROTECTED == 0 {
			t.Errorf("%s: access list inherits from the data folder (%v)", path, err)
		}
		dacl, _, err := sd.DACL()
		if err != nil || dacl == nil {
			t.Fatalf("%s: no access list: %v", path, err)
		}
		if dacl.AceCount == 0 {
			t.Fatalf("%s: an empty access list", path)
		}
		for i := range uint32(dacl.AceCount) {
			var ace *windows.ACCESS_ALLOWED_ACE
			if err := windows.GetAce(dacl, i, &ace); err != nil {
				t.Fatal(err)
			}
			sid := (*windows.SID)(unsafe.Pointer(&ace.SidStart))
			if ace.Header.AceType != windows.ACCESS_ALLOWED_ACE_TYPE || !sid.Equals(user.User.Sid) {
				t.Errorf("%s: entry %d of %d is not the server's account", path, i, dacl.AceCount)
			}
		}
	}
}
