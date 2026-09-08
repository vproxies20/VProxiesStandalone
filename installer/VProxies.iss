#define MyAppName "VProxies SA"
#define MyAppVersion "1.2.0"
#define MyAppPublisher "VProxies"
#define MyAppExeName "VProxiesSA.exe"

[Setup]
AppId={{A7B81D65-9C2F-4E8E-A2E2-46F544F0A19B}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\VProxiesSA
DefaultGroupName=VProxies SA
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\artifacts
OutputBaseFilename=VProxiesSASetup-{#MyAppVersion}-win-x64
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupIconFile=..\src\VProxies.App\assets\vproxies.ico
CloseApplications=yes
RestartApplications=no

[Files]
Source: "..\artifacts\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\runtime\sing-box.exe"; DestDir: "{app}\runtime"; Flags: ignoreversion
Source: "..\runtime\wintun.dll"; DestDir: "{app}\runtime"; Flags: ignoreversion skipifsourcedoesntexist
Source: "..\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist
Source: "..\THIRD-PARTY-NOTICES.md"; DestDir: "{app}\licenses"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\VProxies SA"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\VProxies SA"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch VProxies SA"; Flags: nowait postinstall skipifsilent runascurrentuser
