package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"

	"watchmymedia/server/internal/hwdevice"
)

func newAdapterScanner() hwdevice.Scanner {
	executable, err := os.Executable()
	if err != nil {
		return hwdevice.NewScanner("")
	}
	return hwdevice.NewScanner(executable)
}

// This command does not load configuration, databases, credentials or media.
// Only the internal child mode enters a driver; normal callers get deadlines.
func gpuAdaptersCommand(ctx context.Context, args []string, out io.Writer, scan hwdevice.Scanner) error {
	if len(args) == 2 && args[0] == "--native" {
		b, err := hwdevice.WriteNative(args[1])
		if err != nil {
			return err
		}
		_, err = out.Write(append(b, '\n'))
		return err
	}
	if len(args) != 0 {
		return errors.New("gpu-adapters takes no flags")
	}
	i := hwdevice.Inventory{Adapters: []hwdevice.Adapter{}, Diagnostics: []hwdevice.EnumerationDiagnostic{}}
	if scan != nil {
		i = scan(ctx)
	}
	encoder := json.NewEncoder(out)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(i); err != nil {
		return err
	}
	return ctx.Err()
}
