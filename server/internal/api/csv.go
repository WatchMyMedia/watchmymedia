package api

import (
	"strings"
	"unicode"
)

// csvCells keeps untrusted text from becoming a spreadsheet formula. CSV
// quoting alone only escapes the file format; spreadsheet imports still
// interpret formulas, including ones preceded by whitespace or a BOM.
func csvCells(cells []string) []string {
	for i, cell := range cells {
		trimmed := strings.TrimLeftFunc(cell, func(r rune) bool { return unicode.IsSpace(r) || r == '\ufeff' })
		if strings.ContainsAny(cell, "\t\r\n") || (trimmed != "" && strings.ContainsRune("=+-@", rune(trimmed[0]))) {
			cells[i] = "'" + cell
		}
	}
	return cells
}
