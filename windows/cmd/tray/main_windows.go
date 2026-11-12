package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"watchmymedia/windows/internal/platform"
	"watchmymedia/windows/internal/server"
	"watchmymedia/windows/internal/winui"
)

var version = "development"
var report = winui.Error

func main() {
	quit := flag.Bool("quit", false, "Ask this user's running tray app to quit")
	noBrowser := flag.Bool("no-browser", false, "Do not open setup automatically (test environments)")
	data := flag.String("data-dir", "", "Override the per-user data directory (test environments)")
	port := flag.Int("port", 0, "Override the server port")
	desktop := flag.String("desktop-action", "", "Installer action in the desktop user's account")
	enableLogin := flag.Bool("enable-login", false, "Enable sign-in startup for this user")
	disableLogin := flag.Bool("disable-login", false, "Disable sign-in startup for this user")
	removeData := flag.Bool("remove-data", false, "Remove this user's default data after quitting")
	configureOnly := flag.Bool("configure-login", false, "Save the startup preference and exit")
	checkIdle := flag.Bool("check-install-idle", false, "Check that no other process uses this installation")
	checkPlatform := flag.Bool("check-platform", false, "Check desktop requirements without starting or showing a dialog")
	platformReport := flag.String("platform-report", "", "Write a local desktop-platform JSON report and exit")
	flag.Parse()
	report = func(e error) {
		if *noBrowser {
			fmt.Fprintln(os.Stderr, e)
		} else {
			winui.Error(e)
		}
	}
	if *checkPlatform || *platformReport != "" {
		r := platform.Inspect()
		if *platformReport != "" {
			b, err := json.MarshalIndent(r, "", "  ")
			if err == nil {
				err = os.WriteFile(*platformReport, append(b, '\n'), 0o600)
			}
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				os.Exit(1)
			}
		}
		if !r.Allowed {
			os.Exit(platform.UnsupportedExit)
		}
		return
	}
	if *checkIdle {
		if e := winui.CheckInstallIdle(); e != nil {
			report(e)
			os.Exit(1)
		}
		return
	}
	if *desktop != "" {
		if e := winui.DesktopAction(*desktop, *enableLogin); e != nil {
			report(e)
			os.Exit(1)
		}
		return
	}
	// Stop/uninstall can still clean up an existing installation. Every
	// normal launch and enabling sign-in startup requires a supported PC.
	if !*quit && !(*configureOnly && *disableLogin) {
		if r := platform.Inspect(); !r.Allowed {
			report(errors.New(r.Reason))
			os.Exit(platform.UnsupportedExit)
		}
	}
	if *enableLogin || *disableLogin {
		if e := winui.ConfigureLogin(*enableLogin && !*disableLogin); e != nil {
			report(e)
			os.Exit(1)
		}
	}
	if *configureOnly {
		return
	}
	if *quit {
		if e := winui.QuitExisting(); e != nil {
			report(e)
			os.Exit(1)
		}
		if *removeData {
			base := os.Getenv("LOCALAPPDATA")
			if base == "" {
				report(fmt.Errorf("Windows did not provide Local AppData"))
				os.Exit(1)
			}
			if e := os.RemoveAll(filepath.Join(base, "WatchMyMedia")); e != nil {
				report(e)
				os.Exit(1)
			}
		}
		return
	}

	if *data == "" {
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			report(fmt.Errorf("Windows did not provide Local AppData"))
			os.Exit(1)
		}
		*data = filepath.Join(base, "WatchMyMedia")
	}
	if *port == 0 {
		if p, e := strconv.Atoi(os.Getenv("WATCHMYMEDIA_PORT")); e == nil {
			*port = p
		}
	}
	absolute, e := filepath.Abs(*data)
	if e != nil {
		report(e)
		os.Exit(1)
	}
	*data = absolute
	if *port == 0 {
		*port = server.LoadPort(*data)
	}
	if *port < 1 || *port > 65535 {
		report(fmt.Errorf("port must be between 1 and 65535"))
		os.Exit(1)
	}
	exe, e := os.Executable()
	if e != nil {
		report(e)
		os.Exit(1)
	}
	cfg := server.Config{Executable: filepath.Join(filepath.Dir(exe), "watchmymedia-server.exe"), DataDir: *data, Port: *port}
	if !*noBrowser {
		cfg.OpenSetup = winui.OpenURL
	}
	if e = winui.Run(server.New(cfg), version); e != nil {
		report(e)
		os.Exit(1)
	}
}
