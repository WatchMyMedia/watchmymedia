// Package winui owns a Win32 message loop and the native notification-area menu.
// It uses the system's menu theme, keyboard handling and DPI scaling.
package winui

import (
	"context"
	"errors"
	"fmt"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"unsafe"
	"watchmymedia/windows/internal/server"
)

var user32 = windows.NewLazySystemDLL("user32.dll")
var shell32 = windows.NewLazySystemDLL("shell32.dll")
var kernel32 = windows.NewLazySystemDLL("kernel32.dll")
var registerClass = user32.NewProc("RegisterClassExW")
var createWindow = user32.NewProc("CreateWindowExW")
var defWindow = user32.NewProc("DefWindowProcW")
var getMessage = user32.NewProc("GetMessageW")
var dispatchMessage = user32.NewProc("DispatchMessageW")
var translateMessage = user32.NewProc("TranslateMessage")
var postMessage = user32.NewProc("PostMessageW")
var postQuit = user32.NewProc("PostQuitMessage")
var registerMessage = user32.NewProc("RegisterWindowMessageW")
var notifyIcon = shell32.NewProc("Shell_NotifyIconW")
var createMenu = user32.NewProc("CreatePopupMenu")
var appendMenu = user32.NewProc("AppendMenuW")
var trackMenu = user32.NewProc("TrackPopupMenu")
var destroyMenu = user32.NewProc("DestroyMenu")
var getCursor = user32.NewProc("GetCursorPos")
var setForeground = user32.NewProc("SetForegroundWindow")
var setTimer = user32.NewProc("SetTimer")
var killTimer = user32.NewProc("KillTimer")
var destroyWindow = user32.NewProc("DestroyWindow")
var loadImage = user32.NewProc("LoadImageW")
var destroyIcon = user32.NewProc("DestroyIcon")
var messageBox = user32.NewProc("MessageBoxW")
var powerCreate = kernel32.NewProc("PowerCreateRequest")
var powerSet = kernel32.NewProc("PowerSetRequest")
var powerClear = kernel32.NewProc("PowerClearRequest")

const callbackMessage = 0x8001
const closeMessage = 0x8002

type point struct{ X, Y int32 }
type msg struct {
	Window         uintptr
	Message        uint32
	WParam, LParam uintptr
	Time           uint32
	Point          point
	Private        uint32
}
type windowClass struct {
	Size, Style                        uint32
	Proc                               uintptr
	ClassExtra, WindowExtra            int32
	Instance, Icon, Cursor, Background uintptr
	Menu, Name                         *uint16
	SmallIcon                          uintptr
}
type iconData struct {
	Size               uint32
	Window             uintptr
	ID, Flags, Message uint32
	Icon               uintptr
	Tip                [128]uint16
	State, StateMask   uint32
	Info               [256]uint16
	Version            uint32
	Title              [64]uint16
	InfoFlags          uint32
	GUID               windows.GUID
	BalloonIcon        uintptr
}
type app struct {
	updater    *updater
	window     uintptr
	icon       iconData
	icons      [2]uintptr
	controller *server.Controller
	version    string
	cancel     context.CancelFunc
	done       chan struct{}
	closing    bool
	power      windows.Handle
	taskbar    uint32
}

var active *app // only accessed on the pinned message-loop thread
func u16(s string) *uint16 {
	p, _ := windows.UTF16PtrFromString(strings.ReplaceAll(s, "\x00", ""))
	return p
}
func Error(e error) {
	messageBox.Call(0, uintptr(unsafe.Pointer(u16(e.Error()))), uintptr(unsafe.Pointer(u16("WatchMyMedia"))), 0x10)
}
func OpenURL(url string) {
	if e := windows.ShellExecute(0, u16("open"), u16(url), nil, nil, 1); e != nil {
		Error(e)
	}
}
func objectName(suffix string) (*uint16, error) {
	u, e := windows.GetCurrentProcessToken().GetTokenUser()
	if e != nil {
		return nil, e
	}
	return windows.UTF16PtrFromString(`Local\WatchMyMedia-` + u.User.Sid.String() + "-" + suffix)
}
func QuitExisting() error {
	n, e := objectName("quit")
	if e != nil {
		return e
	}
	h, e := windows.OpenEvent(windows.EVENT_MODIFY_STATE, false, n)
	if errors.Is(e, windows.ERROR_FILE_NOT_FOUND) {
		return nil
	}
	if e != nil {
		return e
	}
	defer windows.CloseHandle(h)
	if e := windows.SetEvent(h); e != nil {
		return e
	}
	return waitForQuit()
}

func Run(c *server.Controller, version string) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	name, e := objectName("instance")
	if e != nil {
		return e
	}
	mutex, e := windows.CreateMutex(nil, false, name)
	if errors.Is(e, windows.ERROR_ALREADY_EXISTS) {
		if mutex != 0 {
			windows.CloseHandle(mutex)
		}
		n, _ := objectName("open")
		h, e := windows.OpenEvent(windows.EVENT_MODIFY_STATE, false, n)
		if e != nil {
			return e
		}
		defer windows.CloseHandle(h)
		return windows.SetEvent(h)
	}
	if e != nil {
		return e
	}
	defer windows.CloseHandle(mutex)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	a := &app{controller: c, version: version, cancel: cancel, done: make(chan struct{})}
	active = a
	defer func() { active = nil }()
	var instance windows.Handle
	if e := windows.GetModuleHandleEx(0, nil, &instance); e != nil {
		return e
	}
	wc := windowClass{Instance: uintptr(instance), Proc: syscall.NewCallback(windowProc), Name: u16("WatchMyMedia.Tray")}
	wc.Size = uint32(unsafe.Sizeof(wc))
	if r, _, e := registerClass.Call(uintptr(unsafe.Pointer(&wc))); r == 0 {
		return fmt.Errorf("register tray window: %w", e)
	}
	h, _, e := createWindow.Call(0, uintptr(unsafe.Pointer(wc.Name)), uintptr(unsafe.Pointer(wc.Name)), 0, 0, 0, 0, 0, 0, 0, uintptr(instance), 0)
	if h == 0 {
		return fmt.Errorf("create tray window: %w", e)
	}
	a.window = h
	defer destroyWindow.Call(h)
	a.icon = iconData{Window: h, ID: 1, Flags: 1 | 2 | 4 | 0x80, Message: callbackMessage}
	a.icon.Size = uint32(unsafe.Sizeof(a.icon))
	for i, id := range []uintptr{2, 3} {
		a.icons[i], _, _ = loadImage.Call(uintptr(instance), id, 1, 0, 0, 0x40)
		if a.icons[i] == 0 {
			return fmt.Errorf("load tray icon resource %d", id)
		}
		defer destroyIcon.Call(a.icons[i])
	}
	a.taskbar = uint32(call(registerMessage, uintptr(unsafe.Pointer(u16("TaskbarCreated")))))
	if !a.refreshIcon(0) {
		return fmt.Errorf("Windows could not add the notification-area icon")
	}
	defer notifyIcon.Call(2, uintptr(unsafe.Pointer(&a.icon)))
	a.updater, e = newUpdater(version, func() { postMessage.Call(h, closeMessage, 0, 0) })
	if e != nil {
		return fmt.Errorf("initialise Windows updates: %w", e)
	}
	defer a.updater.close()
	setTimer.Call(h, 1, 1000, 0)
	defer killTimer.Call(h, 1)
	// Installer and second-launch events are per user, not per data directory:
	// one app per user, with an explicit port conflict for other users.
	for _, kind := range []string{"quit", "open"} {
		n, e := objectName(kind)
		if e != nil {
			return e
		}
		event, e := windows.CreateEvent(nil, 0, 0, n)
		if e != nil {
			return e
		}
		defer windows.CloseHandle(event)
		go func(kind string, event windows.Handle) {
			for {
				r, e := windows.WaitForSingleObject(event, 500)
				if e != nil {
					return
				}
				select {
				case <-ctx.Done():
					return
				default:
				}
				if r == windows.WAIT_OBJECT_0 {
					m := uintptr(closeMessage)
					if kind == "open" {
						m = 0x8003
					}
					postMessage.Call(h, m, 0, 0)
				}
			}
		}(kind, event)
	}
	go func() { c.Run(ctx); close(a.done) }()
	defer func() { cancel(); <-a.done; a.sleep(false) }()
	var m msg
	for {
		r, _, e := getMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) == -1 {
			return e
		}
		if r == 0 {
			return nil
		}
		translateMessage.Call(uintptr(unsafe.Pointer(&m)))
		dispatchMessage.Call(uintptr(unsafe.Pointer(&m)))
	}
}
func call(p *windows.LazyProc, args ...uintptr) uintptr { r, _, _ := p.Call(args...); return r }
func windowProc(hwnd uintptr, message uint32, w, l uintptr) uintptr {
	a := active
	if a == nil {
		return call(defWindow, hwnd, uintptr(message), w, l)
	}
	switch message {
	case callbackMessage:
		switch uint16(l) {
		case 0x7b:
			a.menu()
		case 0x400, 0x401:
			a.open("")
		}
		return 0
	case 0x8003:
		a.open("")
		return 0
	case 0x113:
		a.refreshIcon(1)
		a.sleep(a.controller.Snapshot().Playing())
		return 0
	case 0x1a:
		a.refreshIcon(1)
		return 0
	case 0x11:
		a.beginClose()
		return 1 // WM_QUERYENDSESSION
	case 0x16:
		if w != 0 {
			a.beginClose()
		}
		return 0
	case closeMessage, 0x10:
		a.beginClose()
		return 0
	case 0x8004:
		postQuit.Call(0)
		return 0
	}
	if message == a.taskbar {
		a.refreshIcon(0)
		return 0
	}
	return call(defWindow, hwnd, uintptr(message), w, l)
}
func (a *app) beginClose() {
	if a.closing {
		return
	}
	a.closing = true
	a.cancel()
	go func() { <-a.done; postMessage.Call(a.window, 0x8004, 0, 0) }()
}
func (a *app) open(path string) {
	if s := a.controller.Snapshot(); s.State == "running" {
		go OpenURL(server.URL(s.Port, path))
	}
}
func (a *app) refreshIcon(action uintptr) bool {
	index := 0
	if k, e := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`, registry.QUERY_VALUE); e == nil {
		v, _, _ := k.GetIntegerValue("SystemUsesLightTheme")
		k.Close()
		if v == 0 {
			index = 1
		}
	}
	a.icon.Icon = a.icons[index]
	a.icon.Tip = [128]uint16{}
	tip, _ := windows.UTF16FromString("WatchMyMedia · " + a.controller.Snapshot().Label())
	if len(tip) > 128 {
		tip = append(tip[:127], 0)
	}
	copy(a.icon.Tip[:], tip)
	if call(notifyIcon, action, uintptr(unsafe.Pointer(&a.icon))) == 0 {
		return false
	}
	if action == 0 {
		a.icon.Version = 4
		notifyIcon.Call(4, uintptr(unsafe.Pointer(&a.icon)))
	}
	return true
}
func (a *app) menu() {
	m := call(createMenu)
	if m == 0 {
		return
	}
	defer destroyMenu.Call(m)
	add := func(id uintptr, text string, flags uintptr) {
		appendMenu.Call(m, flags, id, uintptr(unsafe.Pointer(u16(text))))
	}
	sep := func() { add(0, "", 0x800) }
	s := a.controller.Snapshot()
	add(0, "WatchMyMedia · "+a.version, 2)
	add(0, s.Label(), 2)
	sep()
	if s.State == "conflict" {
		add(8, "Use another port", 0)
	} else {
		if s.Status.SetupRequired || s.Status.SetupIncomplete {
			add(2, "Finish setup…", 0)
		}
		flags := uintptr(0)
		if s.State != "running" {
			flags = 2
		}
		add(1, "Open WatchMyMedia", flags)
		if s.State == "running" {
			sep()
			if len(s.Status.Plays) == 0 && len(s.Status.Sessions) == 0 {
				add(0, "No streams playing", 2)
			}
			for i, p := range s.Status.Plays {
				if i == 5 {
					add(0, "More in Activity…", 2)
					break
				}
				add(0, menuText(p.User+" · "+p.Title+" · "+p.Mode), 2)
			}
			if len(s.Status.Plays) == 0 {
				for _, p := range s.Status.Sessions {
					add(0, menuText("Streaming · "+p.Encoder), 2)
				}
			}
			add(3, "Open Activity", 0)
		}
	}
	sep()
	checked := uintptr(0)
	if loginEnabled() {
		checked = 8
	}
	add(4, "Start at sign-in", checked)
	add(5, "Show logs", 0)
	add(6, "Restart server", 0)
	if s.State == "running" {
		add(7, "Check for updates…", 0)
	}
	sep()
	add(9, "Quit WatchMyMedia", 0)
	var p point
	getCursor.Call(uintptr(unsafe.Pointer(&p)))
	setForeground.Call(a.window)
	id := call(trackMenu, m, 0x100|2, uintptr(p.X), uintptr(p.Y), 0, a.window, 0)
	postMessage.Call(a.window, 0, 0, 0)
	switch id {
	case 1:
		a.open("")
	case 2:
		a.open("setup")
	case 3:
		a.open("activity")
	case 4:
		if e := setLogin(!loginEnabled()); e != nil {
			Error(e)
		}
	case 5:
		exe, _ := windows.GetSystemDirectory()
		e := windows.ShellExecute(0, u16("open"), u16(filepath.Join(exe, "explorer.exe")), u16(`/select,"`+a.controller.LogPath()+`"`), nil, 1)
		if e != nil {
			Error(e)
		}
	case 6:
		a.controller.Restart(false)
	case 7:
		if a.updater != nil {
			a.updater.check()
		} else {
			a.open("settings")
		}
	case 8:
		a.controller.Restart(true)
	case 9:
		a.beginClose()
	}
}
func menuText(s string) string {
	s = strings.ReplaceAll(strings.ReplaceAll(s, "&", "&&"), "\t", " ")
	r := []rune(s)
	if len(r) > 100 {
		return string(r[:99]) + "…"
	}
	return s
}

const runKey = `Software\Microsoft\Windows\CurrentVersion\Run`

func loginEnabled() bool {
	k, e := registry.OpenKey(registry.CURRENT_USER, runKey, registry.QUERY_VALUE)
	if e != nil {
		return false
	}
	defer k.Close()
	v, _, e := k.GetStringValue("WatchMyMedia")
	exe, _ := os.Executable()
	return e == nil && v == `"`+exe+`"`
}
func setLogin(on bool) error {
	k, _, e := registry.CreateKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if e != nil {
		return e
	}
	defer k.Close()
	if !on {
		e = k.DeleteValue("WatchMyMedia")
		if errors.Is(e, windows.ERROR_FILE_NOT_FOUND) {
			return nil
		}
		return e
	}
	exe, e := os.Executable()
	if e != nil {
		return e
	}
	return k.SetStringValue("WatchMyMedia", `"`+exe+`"`)
}
func (a *app) sleep(on bool) {
	if !on {
		if a.power != 0 {
			powerClear.Call(uintptr(a.power), 1)
			windows.CloseHandle(a.power)
			a.power = 0
		}
		return
	}
	if a.power != 0 {
		return
	}
	type reason struct {
		Version, Flags uint32
		Text           *uint16
		Reserved       [2]uintptr
	}
	r := reason{Flags: 1, Text: u16("WatchMyMedia is streaming")}
	h, _, _ := powerCreate.Call(uintptr(unsafe.Pointer(&r)))
	if h == 0 || h == ^uintptr(0) {
		return
	}
	if call(powerSet, h, 1) == 0 {
		windows.CloseHandle(windows.Handle(h))
		return
	}
	a.power = windows.Handle(h)
}
