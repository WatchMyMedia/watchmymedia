package hwdevice

import "testing"

func TestAdapterIndicesAreBackendSpecific(t *testing.T) {
	for _, tc := range []struct {
		hw, os string
		n      int
		ok     bool
	}{
		{"auto", "windows", -1, true}, {"auto", "windows", 0, false}, {"software", "windows", 0, false},
		{"nvenc", "windows", 0, true}, {"nvenc", "linux", 1, true}, {"nvenc", "windows", -2, false},
		{"qsv", "windows", 1, true}, {"amf", "windows", 2, true}, {"qsv", "linux", 0, true}, {"qsv", "darwin", 0, false},
		{"vaapi", "linux", 1, true}, {"vaapi", "windows", 0, false}, {"amf", "linux", 0, false},
		{"amf", "darwin", 0, false}, {"videotoolbox", "darwin", 0, false},
	} {
		if err := validate(tc.hw, &tc.n, tc.os); (err == nil) != tc.ok {
			t.Errorf("%+v: %v", tc, err)
		}
	}
	bad := -2
	if Index(&bad) == nil {
		t.Fatal("invalid index was silently changed to the default")
	}
	n := 0
	got := Index(&n)
	n = 3
	if got == nil || *got != 0 || Index(nil) != nil {
		t.Fatal("index did not preserve an explicit zero independently")
	}
}
