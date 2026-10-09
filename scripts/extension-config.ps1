function Resolve-ExtensionId {
    param([string]$ExplicitId, [Parameter(Mandatory=$true)][string]$EnvPath)
    $resolvedId = $ExplicitId
    if ([string]::IsNullOrWhiteSpace($resolvedId)) {
        if (-not (Test-Path -LiteralPath $EnvPath -PathType Leaf)) {
            throw 'Crie o .env na raiz com CHROME_EXTENSION_ID ou informe -ExtensionId.'
        }
        # O arquivo e lido como dados; nenhum comando ou expressao do .env e executado.
        $values = @(Get-Content -LiteralPath $EnvPath -Encoding UTF8 | ForEach-Object {
            if ($_ -match '^\s*CHROME_EXTENSION_ID\s*=\s*(.*?)\s*$') { $Matches[1] }
        })
        if ($values.Count -ne 1) { throw 'O .env deve conter exatamente uma chave CHROME_EXTENSION_ID.' }
        $resolvedId = $values[0].Trim()
        if ($resolvedId.Length -ge 2) {
            $first = $resolvedId[0]
            $last = $resolvedId[$resolvedId.Length - 1]
            if (($first -eq '"' -or $first -eq "'") -and $first -eq $last) {
                $resolvedId = $resolvedId.Substring(1, $resolvedId.Length - 2)
            }
        }
    }
    if ($resolvedId -cnotmatch '^[a-p]{32}$') {
        throw 'CHROME_EXTENSION_ID deve ser o ID do Chrome: 32 letras minusculas de a ate p.'
    }
    return $resolvedId
}
