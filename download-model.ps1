$ErrorActionPreference = 'Stop'
$assets = Join-Path $PSScriptRoot 'app\src\main\assets'
New-Item -ItemType Directory -Force $assets | Out-Null
# Japanese-only build: remove old Russian/English models if present
foreach ($old in @('model-ru', 'model-en')) {
    $path = Join-Path $assets $old
    if (Test-Path $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}
@(
    @{ Name = 'ja'; Version = 'vosk-model-small-ja-0.22'; UUID = 'ja-small-0.22-v1' }
) | ForEach-Object {
    $zip = Join-Path $env:TEMP ($_.Version + '.zip')
    $model = Join-Path $assets ('model-' + $_.Name)
    if (-not (Test-Path $model)) {
        Invoke-WebRequest ('https://alphacephei.com/vosk/models/' + $_.Version + '.zip') -OutFile $zip
        Expand-Archive -LiteralPath $zip -DestinationPath $assets -Force
        Move-Item -LiteralPath (Join-Path $assets $_.Version) -Destination $model
    }
    Set-Content -LiteralPath (Join-Path $model 'uuid') -Value $_.UUID -Encoding ascii
}
