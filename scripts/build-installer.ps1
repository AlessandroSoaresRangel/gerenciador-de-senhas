param([string]$ExtensionId = '', [string]$CompilerPath = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'installer-config.ps1')
$configuration = Get-InstallerExtensionConfiguration -ExtensionId $ExtensionId
$projectPath = Split-Path $PSScriptRoot -Parent
if ($CompilerPath -eq '') {
    $candidates = @(
        "${env:ProgramFiles(x86)}/Inno Setup 6/ISCC.exe",
        "$env:LOCALAPPDATA/Programs/Inno Setup 6/ISCC.exe"
    )
    $CompilerPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $CompilerPath -or -not (Test-Path -LiteralPath $CompilerPath)) {
    throw 'Instale Inno Setup 6 ou informe -CompilerPath com o caminho do ISCC.exe.'
}
$javaProcessInfo = [System.Diagnostics.ProcessStartInfo]::new('java', '-XshowSettings:properties -version')
$javaProcessInfo.UseShellExecute = $false
$javaProcessInfo.CreateNoWindow = $true
$javaProcessInfo.RedirectStandardError = $true
$javaProcess = [System.Diagnostics.Process]::Start($javaProcessInfo)
$javaSettings = $javaProcess.StandardError.ReadToEnd()
$javaProcess.WaitForExit()
$javaProcess.Dispose()
$javaHomeMatch = [regex]::Match($javaSettings, 'java.home\s*=\s*([^\r\n]+)')
if (-not $javaHomeMatch.Success) { throw 'Nao foi possivel localizar o JDK.' }
$packagerPath = Join-Path $javaHomeMatch.Groups[1].Value.Trim() 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $packagerPath)) { throw 'O JDK precisa incluir jpackage.' }
Push-Location $projectPath
try {
    # Cada build recebe uma pasta nova; nenhum pacote anterior e removido.
    $stagePath = Join-Path $projectPath ('target/installer-stage-' + [guid]::NewGuid().ToString('N'))
    $inputPath = Join-Path $stagePath 'input'
    & mvn.cmd package dependency:copy-dependencies '-DincludeScope=runtime' "-DoutputDirectory=$inputPath"
    if ($LASTEXITCODE -ne 0) { throw 'Falha no build ou nos testes Maven.' }
    Copy-Item -LiteralPath target/cofre-local-1.0.0.jar -Destination $inputPath
    & $packagerPath --type app-image --input $inputPath --dest $stagePath --name CofreLocal --main-jar cofre-local-1.0.0.jar --main-class br.com.cofre.desktop.VaultApp --java-options '--module-path=$APPDIR' --java-options '--add-modules=javafx.controls'
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao empacotar desktop.' }
    & $packagerPath --type app-image --input $inputPath --dest $stagePath --name CofreNativeHost --main-jar cofre-local-1.0.0.jar --main-class br.com.cofre.nativehost.NativeHostMain --win-console
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao empacotar Native Host.' }
    & $CompilerPath "/DStagePath=$stagePath" "/DExtensionId=$($configuration.ExtensionId)" (Join-Path $PSScriptRoot '../installer/cofre-local.iss')
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar instalador.' }
    Write-Host 'Instalador gerado em target/installer-output/CofreLocal-Setup.exe'
} finally {
    Pop-Location
}
