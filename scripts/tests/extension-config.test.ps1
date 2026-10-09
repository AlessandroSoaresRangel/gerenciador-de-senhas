$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../extension-config.ps1')

function Assert-Equal($Actual, $Expected) {
    if ($Actual -cne $Expected) { throw "Esperado '$Expected', recebido '$Actual'." }
}
function Assert-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Configuracao invalida deveria ser recusada.' }
}

$testPath = Join-Path ([System.IO.Path]::GetTempPath()) ('cofre-config-' + [guid]::NewGuid().ToString('N') + '.env')
$validId = 'abcdefghijklmnopabcdefghijklmnop'
try {
    Assert-Rejected { Resolve-ExtensionId -EnvPath $testPath }
    [System.IO.File]::WriteAllText($testPath, "# Configuracao local`nOUTRA_CHAVE=teste`nCHROME_EXTENSION_ID = '$validId'`n")
    Assert-Equal (Resolve-ExtensionId -EnvPath $testPath) $validId
    Assert-Equal (Resolve-ExtensionId -ExplicitId ('a' * 32) -EnvPath $testPath) ('a' * 32)
    [System.IO.File]::WriteAllText($testPath, 'CHROME_EXTENSION_ID=invalido')
    Assert-Rejected { Resolve-ExtensionId -EnvPath $testPath }
    Assert-Rejected { Resolve-ExtensionId -ExplicitId 'invalido' -EnvPath $testPath }
    [System.IO.File]::WriteAllText($testPath, "CHROME_EXTENSION_ID=$validId`nCHROME_EXTENSION_ID=$validId")
    Assert-Rejected { Resolve-ExtensionId -EnvPath $testPath }
    Write-Host 'Todos os testes de configuracao passaram.'
} finally {
    [System.IO.File]::Delete($testPath)
}
