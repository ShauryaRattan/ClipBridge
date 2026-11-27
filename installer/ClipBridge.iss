; Inno Setup script - makes dist\ClipBridge-Setup-1.0.exe, a normal Windows installer.
; Steps: 1) run build.bat   2) install Inno Setup (free, jrsoftware.org)   3) open this file and press F9 (Compile).
; The installer does not need administrator rights (it installs for the current user).

#define AppName "ClipBridge"
#define AppVersion "1.0"
#define AppPublisher "Shaurya Rattan"

[Setup]
AppId={{6F1D6C1E-3B0A-4C55-9E0B-C11B8D6A2F11}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=ClipBridge-Setup-{#AppVersion}
SetupIconFile=..\assets\clipbridge.ico
UninstallDisplayIcon={app}\ClipBridge.exe
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=lowest
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Create a desktop icon"; GroupDescription: "Additional icons:"
Name: "autostart"; Description: "Start {#AppName} when I sign in to Windows"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
Source: "..\dist\ClipBridge\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\ClipBridge.exe"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\ClipBridge.exe"; Tasks: desktopicon
Name: "{userstartup}\{#AppName}"; Filename: "{app}\ClipBridge.exe"; Tasks: autostart

[Run]
Filename: "{app}\ClipBridge.exe"; Description: "Start {#AppName} now"; Flags: nowait postinstall skipifsilent
