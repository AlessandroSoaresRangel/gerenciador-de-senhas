#ifndef StagePath
  #error StagePath deve indicar os app-images produzidos pelo jpackage.
#endif
#ifndef ExtensionId
  #define ExtensionId ""
#endif
[Setup]
AppId={{63AC0328-9806-4DC2-AD93-9BD66B35E634}
AppName=Cofre local
AppVersion=1.0.0
DefaultDirName={localappdata}\Programs\CofreLocal
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\target\installer-output
OutputBaseFilename=CofreLocal-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\desktop\CofreLocal.exe

[Languages]
Name: "portuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Files]
Source: "{#StagePath}\CofreLocal\*"; DestDir: "{app}\desktop"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#StagePath}\CofreNativeHost\*"; DestDir: "{app}\host"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Cofre local"; Filename: "{app}\desktop\CofreLocal.exe"
Name: "{userdesktop}\Cofre local"; Filename: "{app}\desktop\CofreLocal.exe"

[Code]
var
  ExtensionPage: TInputQueryWizardPage;

function ValidExtensionId(Value: String): Boolean;
var Index: Integer;
begin
  Result := False;
  if Length(Value) <> 32 then Exit;
  for Index := 1 to Length(Value) do
    if (Value[Index] < 'a') or (Value[Index] > 'p') then Exit;
  Result := True;
end;

procedure InitializeWizard;
begin
  ExtensionPage := CreateInputQueryPage(wpSelectDir, 'Extensão Chrome',
    'Conectar a extensão ao Cofre local',
    'Copie o ID exibido em chrome://extensions. O aplicativo desktop deve estar aberto para usar a extensão.');
  ExtensionPage.Add('ID da extensão:', False);
  ExtensionPage.Values[0] := ExpandConstant('{param:ExtensionId|{#ExtensionId}}');
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = ExtensionPage.ID then begin
    Result := ValidExtensionId(ExtensionPage.Values[0]);
    if not Result then MsgBox('Informe 32 letras minúsculas de a até p.', mbError, MB_OK);
  end;
end;

function JsonPath(Value: String): String;
begin
  StringChangeEx(Value, '\', '\\', True);
  StringChangeEx(Value, '"', '\"', True);
  Result := Value;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var ManifestPath, Origin, Manifest: String;
begin
  if CurStep <> ssPostInstall then Exit;
  if not ValidExtensionId(ExtensionPage.Values[0]) then
    RaiseException('ID da extensão inválido.');
  Origin := 'chrome-extension://' + ExtensionPage.Values[0] + '/';
  ManifestPath := ExpandConstant('{app}\br.com.cofre.local.json');
  Manifest := '{"name":"br.com.cofre.local","description":"Cofre local",' +
    '"path":"' + JsonPath(ExpandConstant('{app}\host\CofreNativeHost.exe')) +
    '","type":"stdio","allowed_origins":["' + Origin + '"]}';
  if not SaveStringToFile(ManifestPath, UTF8Encode(Manifest), False) then
    RaiseException('Não foi possível gravar o manifesto.');
  { O arquivo original foi reinstalado acima: a opção nunca se acumula em upgrades. }
  if not SaveStringToFile(ExpandConstant('{app}\host\app\CofreNativeHost.cfg'),
    (#13#10 + 'java-options=-Dcofre.extensionOrigin=' + Origin + #13#10), True) then
    RaiseException('Não foi possível configurar o host.');
  if not RegWriteStringValue(HKCU32,
    'Software\Google\Chrome\NativeMessagingHosts\br.com.cofre.local', '', ManifestPath) then
    RaiseException('Não foi possível registrar o host no Chrome.');
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var RegisteredPath: String;
begin
  if CurUninstallStep <> usUninstall then Exit;
  { Preserva registros substituídos por outra instalação e todos os bancos/backups. }
  if RegQueryStringValue(HKCU32, 'Software\Google\Chrome\NativeMessagingHosts\br.com.cofre.local', '', RegisteredPath) then
    if RegisteredPath = ExpandConstant('{app}\br.com.cofre.local.json') then
      RegDeleteValue(HKCU32, 'Software\Google\Chrome\NativeMessagingHosts\br.com.cofre.local', '');
  DeleteFile(ExpandConstant('{app}\br.com.cofre.local.json'));
end;
