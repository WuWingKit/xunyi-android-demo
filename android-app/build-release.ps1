param(
    [string]$SigningConfig = 'C:\Users\small\.codex\xunyi-private\release-signing.json',
    [string]$Sdk = 'E:\Android\Sdk',
    [string]$Jdk = 'C:\Program Files\Java\jdk-21'
)
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$build = Join-Path $base 'build-manual'
$output = Join-Path $build 'xunyi-v0.0.1-alpha.apk'
if (-not (Test-Path -LiteralPath $SigningConfig)) {
    throw 'Release signing config missing. Keep the keystore and config outside the repository.'
}
$signing = Get-Content -LiteralPath $SigningConfig -Raw | ConvertFrom-Json
if (-not (Test-Path -LiteralPath $signing.keystore)) { throw 'Release keystore missing.' }
& (Join-Path $base 'build-demo.ps1') -Sdk $Sdk -Jdk $Jdk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
$env:XUNYI_RELEASE_STORE_PASS = $signing.storePassword
$env:XUNYI_RELEASE_KEY_PASS = $signing.keyPassword
try {
    & (Join-Path $Sdk 'build-tools\36.0.0\apksigner.bat') sign `
        --ks $signing.keystore --ks-key-alias $signing.alias `
        --ks-pass env:XUNYI_RELEASE_STORE_PASS --key-pass env:XUNYI_RELEASE_KEY_PASS `
        --out $output (Join-Path $build 'aligned.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signing failed.' }
    & (Join-Path $Sdk 'build-tools\36.0.0\apksigner.bat') verify --verbose $output
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed.' }
} finally {
    Remove-Item Env:XUNYI_RELEASE_STORE_PASS -ErrorAction SilentlyContinue
    Remove-Item Env:XUNYI_RELEASE_KEY_PASS -ErrorAction SilentlyContinue
}
$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText($output + '.sha256', "$hash  xunyi-v0.0.1-alpha.apk`n", [System.Text.UTF8Encoding]::new($false))
Write-Output $output
