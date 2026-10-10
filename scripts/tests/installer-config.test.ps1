$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../installer-config.ps1')
$validId = 'abcdefghijklmnopabcdefghijklmnop'
$configuration = Get-InstallerExtensionConfiguration -ExtensionId $validId
if ($configuration.ExtensionId -cne $validId) { throw 'ID alterado.' }
if ($configuration.Origin -cne "chrome-extension://$validId/") { throw 'Origem incorreta.' }
foreach ($invalidId in @('ABC', ('a' * 31), ('q' * 32), 'a/../../arquivo')) {
    $rejected = $false
    try { Get-InstallerExtensionConfiguration -ExtensionId $invalidId | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'ID invalido aceito.' }
}
$configuration = Get-InstallerExtensionConfiguration
if ($configuration.ExtensionId -ne '') { throw 'Build de teste deve permitir configurar o ID na instalacao.' }
Write-Host 'Testes de configuracao do instalador passaram.'
