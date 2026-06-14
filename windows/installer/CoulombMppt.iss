; Inno Setup script for CoulombMPPT (Windows desktop app).
; Build the installer with:  iscc installer\CoulombMppt.iss
; (or run tools\build-installer.ps1, which publishes then compiles this).
;
; Expects a self-contained publish at ..\publish\win-x64 (see the build script).

#define AppName "CoulombMPPT"
#define AppVersion "0.1.0"
#define AppPublisher "CoulombMPPT"
#define AppExe "CoulombMppt.exe"

[Setup]
; Stable AppId — keep this constant across versions so upgrades replace cleanly.
AppId={{8F3A1E90-7C2B-4D55-9E1A-2B6C4F0A9D11}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#AppExe}
OutputDir=Output
OutputBaseFilename=CoulombMpptSetup-{#AppVersion}
SetupIconFile=..\src\CoulombMppt\Assets\app.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"
Name: "startup";     Description: "Start {#AppName} automatically when I sign in (runs minimised to the tray)"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
; The entire self-contained publish folder.
Source: "..\publish\win-x64\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}";              Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}";    Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}";        Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; Optional auto-start at sign-in (per-user). Removed on uninstall.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; \
    ValueName: "{#AppName}"; ValueData: """{app}\{#AppExe}"""; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#AppExe}"; Description: "Launch {#AppName} now"; Flags: nowait postinstall skipifsilent
