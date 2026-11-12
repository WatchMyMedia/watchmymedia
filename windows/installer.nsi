; The Windows installer. installer.sh --sign defines SIGN_SCRIPT: NSIS then has
; sign.ps1 sign the uninstaller before embedding it and keep a copy at
; SIGNED_UNINSTALLER for verify-installer.ps1; installer.sh signs this
; installer afterwards. Without it the installer and uninstaller are unsigned.
Unicode true
RequestExecutionLevel admin
SetCompressor /SOLID lzma
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"
!include "nsDialogs.nsh"
!ifndef APPDIR
 !error "APPDIR must name the packaged Windows app"
!endif
!ifndef VERSION
 !define VERSION "development"
!endif
!ifndef OUTPUT
 !error "OUTPUT must name the installer file"
!endif
Name "WatchMyMedia"
!ifndef CI_SERVER_HOST
 !define CI_SERVER_HOST "false"
!endif
!if "${CI_SERVER_HOST}" == "true"
 VIProductVersion "0.0.0.0"
 VIAddVersionKey /LANG=1033 "ProductName" "WatchMyMedia (CI host validation only)"
 VIAddVersionKey /LANG=1033 "FileDescription" "WatchMyMedia CI host validation installer"
 VIAddVersionKey /LANG=1033 "FileVersion" "development"
 VIAddVersionKey /LANG=1033 "LegalCopyright" "Eldwick Software LLC"
!endif
!ifdef SIGN_SCRIPT
 !if "${CI_SERVER_HOST}" == "true"
  !error "CI-only installers are never signed"
 !endif
 !ifndef SIGNED_UNINSTALLER
  !error "SIGNED_UNINSTALLER must name where to keep the signed uninstaller"
 !endif
 !uninstfinalize 'pwsh -NoProfile -NonInteractive -File "${SIGN_SCRIPT}" -Path "%1" -KeepCopy "${SIGNED_UNINSTALLER}"' = 0
!endif
OutFile "${OUTPUT}"
InstallDir "$PROGRAMFILES64\WatchMyMedia"
InstallDirRegKey HKLM "Software\WatchMyMedia" "InstallLocation"
ShowInstDetails show
ShowUninstDetails show
Var StartAtLogin
Var StartCheck
Var DeleteData
Var DeleteCheck
!define MUI_ABORTWARNING
!define MUI_ICON "../brand/export/windows/watchmymedia.ico"
!define MUI_UNICON "../brand/export/windows/watchmymedia.ico"
!define MUI_WELCOMEPAGE_TEXT "WatchMyMedia runs your media server from the notification area.$\r$\n$\r$\nAn update stops playback while files are replaced. Your library and settings stay in your Windows user account."
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
Page custom StartupPage StartupLeave
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Open WatchMyMedia"
!define MUI_FINISHPAGE_RUN_FUNCTION StartApp
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
UninstPage custom un.DataPage un.DataLeave
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function .onInit
 ${IfNot} ${RunningX64}
  MessageBox MB_ICONSTOP "WatchMyMedia requires Windows 11 25H2 or later on an x64 PC.$\r$\n$\r$\nUpdate Windows or use a supported PC to run the desktop app." /SD IDOK
  SetErrorLevel 1633
  Quit
 ${EndIf}
 ; One policy implementation: run the packaged tray's headless native check
 ; before touching an existing installation, firewall, registry or user data.
 InitPluginsDir
 SetOutPath "$PLUGINSDIR"
 File /oname=platform-check.exe "${APPDIR}\WatchMyMedia.exe"
 ClearErrors
 ExecWait '"$PLUGINSDIR\platform-check.exe" -check-platform' $0
 ${If} ${Errors}
  StrCpy $0 1633
 ${EndIf}
 ${If} $0 != 0
  MessageBox MB_ICONSTOP "WatchMyMedia requires Windows 11 25H2 or later on an x64 PC.$\r$\n$\r$\nUpdate Windows or use a supported PC to run the desktop app." /SD IDOK
  SetErrorLevel 1633
  Quit
 ${EndIf}
 SetRegView 64
 SetShellVarContext all
 StrCpy $StartAtLogin 1
FunctionEnd
Function StartupPage
 nsDialogs::Create 1018
 Pop $0
 ${NSD_CreateLabel} 0 0 100% 35u "WatchMyMedia starts the server when you open the app. All library and playback settings are in your browser."
 Pop $0
 ${NSD_CreateCheckbox} 0 45u 100% 12u "Start WatchMyMedia when I sign in"
 Pop $StartCheck
 ${NSD_SetState} $StartCheck $StartAtLogin
 nsDialogs::Show
FunctionEnd
Function StartupLeave
 ${NSD_GetState} $StartCheck $StartAtLogin
FunctionEnd
Function StartApp
 ExecWait '"$INSTDIR\WatchMyMedia.exe" -desktop-action start' $1
 ${If} $1 != 0
  MessageBox MB_ICONEXCLAMATION "Windows could not open WatchMyMedia as the desktop user. Start it from the Start menu after closing this installer." /SD IDOK
 ${EndIf}
FunctionEnd
Section "WatchMyMedia" Main
 SetRegView 64
 SetShellVarContext all
 IfFileExists "$INSTDIR\WatchMyMedia.exe" 0 stopped
  ExecWait '"$INSTDIR\WatchMyMedia.exe" -no-browser -desktop-action stop' $0
  ${If} $0 != 0
   MessageBox MB_ICONSTOP "Could not stop WatchMyMedia. Quit the app in each signed-in user's notification area, then run the installer again." /SD IDOK
   SetErrorLevel 1
   Abort
  ${EndIf}
  ExecWait '"$INSTDIR\WatchMyMedia.exe" -no-browser -check-install-idle' $0
  ${If} $0 != 0
   MessageBox MB_ICONSTOP "WatchMyMedia is still running in another Windows session. Quit it there before updating." /SD IDOK
   SetErrorLevel 1
   Abort
  ${EndIf}
 stopped:
 SetOutPath "$INSTDIR"
 SetOverwrite on
 ClearErrors
 File "${APPDIR}\WatchMyMedia.exe"
 File "${APPDIR}\watchmymedia-server.exe"
 File "${APPDIR}\ffmpeg.exe"
 File "${APPDIR}\ffprobe.exe"
 File "${APPDIR}\VERSION"
 File "${APPDIR}\FFMPEG-LICENSE.md"
 File "${APPDIR}\WinSparkle.dll"
 File "${APPDIR}\WINSPARKLE-LICENSE.txt"
 File "${APPDIR}\WINSPARKLE-VERSION"
 ${If} ${Errors}
  MessageBox MB_ICONSTOP "Windows could not replace the application files. Close WatchMyMedia in every signed-in session and run the installer again. Your library data has been kept." /SD IDOK
  SetErrorLevel 1
  Abort
 ${EndIf}
 WriteUninstaller "$INSTDIR\uninstall.exe"
 WriteRegStr HKLM "Software\WatchMyMedia" "InstallLocation" "$INSTDIR"
 WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "DisplayName" "WatchMyMedia"
 WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "DisplayVersion" "${VERSION}"
 WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "Publisher" "Eldwick Software LLC"
 WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "UninstallString" '$\"$INSTDIR\uninstall.exe$\"'
 WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "QuietUninstallString" '$\"$INSTDIR\uninstall.exe$\" /S'
 WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "InstallLocation" "$INSTDIR"
 WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "DisplayIcon" "$INSTDIR\WatchMyMedia.exe"
 WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "NoModify" 1
 WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia" "NoRepair" 1
 CreateDirectory "$SMPROGRAMS\WatchMyMedia"
 CreateShortcut "$SMPROGRAMS\WatchMyMedia\WatchMyMedia.lnk" "$INSTDIR\WatchMyMedia.exe"
 CreateShortcut "$SMPROGRAMS\WatchMyMedia\Uninstall WatchMyMedia.lnk" "$INSTDIR\uninstall.exe"
 ; Program-scoped inbound rules: never allow Public networks.
 nsExec::ExecToStack '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="WatchMyMedia server"'
 Pop $0
 Pop $1
 nsExec::ExecToStack '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="WatchMyMedia server" dir=in action=allow program="$INSTDIR\watchmymedia-server.exe" protocol=TCP profile=private,domain'
 Pop $0
 Pop $1
 ${If} $0 != 0
  MessageBox MB_ICONEXCLAMATION "Windows could not add the private-network firewall rule. Other devices may not reach the server until the rule is added." /SD IDOK
 ${EndIf}
 ; Save the checkbox in the desktop user's registry even if Finish does not launch the app.
 IfSilent startupDone
 StrCpy $0 ""
 ${If} $StartAtLogin == 1
  StrCpy $0 "-enable-login"
 ${EndIf}
 ExecWait '"$INSTDIR\WatchMyMedia.exe" -no-browser -desktop-action startup $0' $1
 ${If} $1 != 0
  MessageBox MB_ICONEXCLAMATION "Windows could not save the startup choice. You can change Start at sign-in from the app's menu." /SD IDOK
 ${EndIf}
 startupDone:
 ; Discovery's UDP rule waits for W0's real Windows mDNS check.
 ${GetParameters} $0
 ${GetOptions} $0 "/START" $1
 ${IfNot} ${Errors}
  Call StartApp
 ${EndIf}
SectionEnd
Function un.onInit
 SetRegView 64
 SetShellVarContext all
 StrCpy $DeleteData 0
FunctionEnd
Function un.DataPage
 nsDialogs::Create 1018
 Pop $0
 ${NSD_CreateLabel} 0 0 100% 35u "The app, shortcuts and firewall rule will be removed. Your library data, accounts and history are kept unless you choose to delete them below."
 Pop $0
 ${NSD_CreateCheckbox} 0 45u 100% 25u "Delete my library data and settings (cannot be undone)"
 Pop $DeleteCheck
 ${NSD_SetState} $DeleteCheck 0
 nsDialogs::Show
FunctionEnd
Function un.DataLeave
 ${NSD_GetState} $DeleteCheck $DeleteData
FunctionEnd
Section "Uninstall"
 SetRegView 64
 SetShellVarContext all
 ExecWait '"$INSTDIR\WatchMyMedia.exe" -no-browser -desktop-action uninstall' $1
 ${If} $1 != 0
  MessageBox MB_ICONSTOP "Could not stop WatchMyMedia or clear its startup setting. Close the app and try uninstalling again. Your data has been kept." /SD IDOK
  SetErrorLevel 1
  Abort
 ${EndIf}
 ExecWait '"$INSTDIR\WatchMyMedia.exe" -no-browser -check-install-idle' $1
 ${If} $1 != 0
  MessageBox MB_ICONSTOP "WatchMyMedia is still running in another Windows session. Quit it there before uninstalling." /SD IDOK
  SetErrorLevel 1
  Abort
 ${EndIf}
 ${If} $DeleteData == 1
  ExecWait '"$INSTDIR\WatchMyMedia.exe" -no-browser -desktop-action remove-data' $1
  ${If} $1 != 0
   MessageBox MB_ICONSTOP "Could not remove your data. The application is still installed." /SD IDOK
   SetErrorLevel 1
   Abort
  ${EndIf}
 ${EndIf}
 nsExec::ExecToStack '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="WatchMyMedia server"'
 Pop $0
 Pop $1
 Delete "$SMPROGRAMS\WatchMyMedia\WatchMyMedia.lnk"
 Delete "$SMPROGRAMS\WatchMyMedia\Uninstall WatchMyMedia.lnk"
 RMDir "$SMPROGRAMS\WatchMyMedia"
 DeleteRegKey HKLM "Software\WatchMyMedia"
 DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WatchMyMedia"
 Delete "$INSTDIR\WatchMyMedia.exe"
 Delete "$INSTDIR\watchmymedia-server.exe"
 Delete "$INSTDIR\ffmpeg.exe"
 Delete "$INSTDIR\ffprobe.exe"
 Delete "$INSTDIR\VERSION"
 Delete "$INSTDIR\FFMPEG-LICENSE.md"
 Delete "$INSTDIR\WinSparkle.dll"
 Delete "$INSTDIR\WINSPARKLE-LICENSE.txt"
 Delete "$INSTDIR\WINSPARKLE-VERSION"
 Delete "$INSTDIR\uninstall.exe"
 RMDir "$INSTDIR"
SectionEnd
