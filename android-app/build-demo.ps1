param(
    [string]$Sdk = 'E:\Android\Sdk',
    [string]$Jdk = 'C:\Program Files\Java\jdk-21'
)
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$build = Join-Path $base 'build-manual'
$res = Join-Path $build 'res'
$classes = Join-Path $build 'classes'
$gen = Join-Path $build 'gen'
New-Item -ItemType Directory -Force $res, $classes, $gen | Out-Null
$credentialFile = if ($env:XUNYI_DEMO_TOKEN_FILE) { $env:XUNYI_DEMO_TOKEN_FILE } else { 'C:\Users\small\.codex\xunyi-private\app-token.txt' }
if (-not (Test-Path -LiteralPath $credentialFile)) { throw 'Demo token file missing; set XUNYI_DEMO_TOKEN_FILE.' }
$credential = (Get-Content -LiteralPath $credentialFile -Raw).Trim()
if ($credential -notmatch '^[a-fA-F0-9]{64,128}$') { throw 'Demo token format invalid.' }
$credentialSource = Join-Path $gen 'cn\xunyi\demo\DemoCredential.java'
New-Item -ItemType Directory -Force (Split-Path $credentialSource) | Out-Null
[System.IO.File]::WriteAllText($credentialSource, "package cn.xunyi.demo; final class DemoCredential { static final String TOKEN = `"$credential`"; }", [System.Text.UTF8Encoding]::new($false))
$tools = Join-Path $Sdk 'build-tools\36.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
Push-Location $base
& (Join-Path $tools 'aapt2.exe') compile --dir 'app\src\main\res' -o 'build-manual\res'
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
$flats = @(Get-ChildItem $res -Filter '*.flat' | ForEach-Object { 'build-manual\res\' + $_.Name })
& (Join-Path $tools 'aapt2.exe') link -o 'build-manual\base.apk' -I $androidJar --min-sdk-version 26 --target-sdk-version 36 --java 'build-manual\gen' --manifest 'app\src\main\AndroidManifest.xml' $flats
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }
$sources = @(Get-ChildItem (Join-Path $base 'app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem $gen -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -source 17 -target 17 -classpath $androidJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$classFiles = @(Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $tools 'd8.bat') --lib $androidJar --min-api 26 --output $build $classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Push-Location $build
try {
    & (Join-Path $Jdk 'bin\jar.exe') uf base.apk classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
    & (Join-Path $tools 'zipalign.exe') -f 4 base.apk aligned.apk
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
    if (-not (Test-Path 'demo-debug.jks')) {
        & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair -keystore demo-debug.jks -storepass android -keypass android -alias demo -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=XunYi Demo' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    }
    & (Join-Path $tools 'apksigner.bat') sign --ks demo-debug.jks --ks-key-alias demo --ks-pass pass:android --key-pass pass:android --out xunyi-demo.apk aligned.apk
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
    & (Join-Path $tools 'apksigner.bat') verify xunyi-demo.apk
if ($LASTEXITCODE -ne 0) { throw 'signature verification failed' }
} finally { Pop-Location; Pop-Location }
Write-Output (Join-Path $build 'xunyi-demo.apk')
