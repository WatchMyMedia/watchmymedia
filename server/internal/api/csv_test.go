package api

import "testing"

func TestCSVFormulaCells(t *testing.T) {
	t.Parallel()
	for _, s := range []string{"=1+1", "+SUM(1,1)", "-1+1", "@SUM(1,1)", "\t=1", "\r=1", "\n=1", "  =1", "\ufeff=1"} {
		if got := csvCells([]string{s})[0]; got != "'"+s {
			t.Errorf("unsafe cell %q -> %q", s, got)
		}
	}
	for _, s := range []string{"", "Living room TV", "Movie, Part 2", "60", "2026-10-31"} {
		if got := csvCells([]string{s})[0]; got != s {
			t.Errorf("ordinary cell %q -> %q", s, got)
		}
	}
}
