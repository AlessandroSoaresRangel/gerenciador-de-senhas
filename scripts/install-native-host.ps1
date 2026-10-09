param([string]$ExtensionId)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'extension-config.ps1')
$ExtensionId = Resolve-ExtensionId -ExplicitId $ExtensionId -EnvPath (Join-Path $projectPath '.env')
Push-Location $projectPath
try {
    $javaProcessInfo = [System.Diagnostics.ProcessStartInfo]::new('java', '-XshowSettings:properties -version')
    $javaProcessInfo.UseShellExecute = $false
    $javaProcessInfo.CreateNoWindow = $true
    $javaProcessInfo.RedirectStandardError = $true
    $javaProcess = [System.Diagnostics.Process]::Start($javaProcessInfo)
    $javaSettings = $javaProcess.StandardError.ReadToEnd()
    $javaProcess.WaitForExit()
    $javaHomeMatch = [regex]::Match($javaSettings, 'java.home\s*=\s*([^\r\n]+)')
    if (-not $javaHomeMatch.Success) { throw 'Não foi possível localizar o JDK.' }
    $packagerPath = Join-Path $javaHomeMatch.Groups[1].Value.Trim() 'bin/jpackage.exe'
    if (-not (Test-Path -LiteralPath $packagerPath)) { throw 'O JDK precisa incluir jpackage.' }
    & mvn.cmd package '-DskipTests' dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/native-input'
    if ($LASTEXITCODE -ne 0) { throw 'Falha no build Maven.' }
    Copy-Item -LiteralPath 'target/cofre-local-1.0.0.jar' -Destination 'target/native-input/'
    $installPath = Join-Path $env:LOCALAPPDATA ('CofreLocal/host-' + [guid]::NewGuid().ToString('N'))
    & $packagerPath --type app-image --input target/native-input --dest $installPath --name CofreNativeHost --main-jar cofre-local-1.0.0.jar --main-class br.com.cofre.nativehost.NativeHostMain --win-console --java-options "-Dcofre.extensionOrigin=chrome-extension://$ExtensionId/"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao empacotar o host. Confira se jpackage está no PATH.' }
    $manifestPath = Join-Path $installPath 'br.com.cofre.local.json'
    $manifest = @{
        name = 'br.com.cofre.local'
        description = 'Consulta de estado do Cofre local'
        path = Join-Path $installPath 'CofreNativeHost/CofreNativeHost.exe'
        type = 'stdio'
        allowed_origins = @("chrome-extension://$ExtensionId/")
    }
    $manifestJson = $manifest | ConvertTo-Json
    [System.IO.File]::WriteAllText($manifestPath, $manifestJson, [System.Text.UTF8Encoding]::new($false))
    $registryPath = 'HKCU:\Software\Google\Chrome\NativeMessagingHosts\br.com.cofre.local'
    New-Item -Path $registryPath -Force | Out-Null
    Set-Item -LiteralPath $registryPath -Value $manifestPath
    Write-Host "Host registrado para a extensão $ExtensionId. Instalação: $installPath"
} finally {
    Pop-Location
}
