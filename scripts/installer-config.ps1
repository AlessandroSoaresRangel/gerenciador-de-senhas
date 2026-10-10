function Get-InstallerExtensionConfiguration {
    param([string]$ExtensionId = '')
    if ($ExtensionId -eq '') { return @{ ExtensionId = ''; Origin = '' } }
    . (Join-Path $PSScriptRoot 'extension-config.ps1')
    $validatedId = Resolve-ExtensionId -ExplicitId $ExtensionId -EnvPath (Join-Path $PSScriptRoot '../.env')
    return @{ ExtensionId = $validatedId; Origin = "chrome-extension://$validatedId/" }
}
