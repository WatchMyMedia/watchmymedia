package api

import (
	"encoding/json"

	"github.com/danielgtaylor/huma/v2"
)

// OpenAPI30 returns the API document as OpenAPI 3.0.3, for code generators
// without full 3.1 support (openapi-generator, which the Android app uses).
// huma's downgrade leaves `const` (from the event stream schema), which 3.0
// does not have; it becomes a one-value enum.
func OpenAPI30(h huma.API) ([]byte, error) {
	raw, err := h.OpenAPI().Downgrade()
	if err != nil {
		return nil, err
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil, err
	}
	constToEnum(v)
	return json.MarshalIndent(v, "", "  ")
}

func constToEnum(v any) {
	switch x := v.(type) {
	case map[string]any:
		if c, ok := x["const"]; ok {
			delete(x, "const")
			x["enum"] = []any{c}
		}
		for _, child := range x {
			constToEnum(child)
		}
	case []any:
		for _, child := range x {
			constToEnum(child)
		}
	}
}
