# MyTVB-Speed-Boost (btr-hardening) 一鍵編譯腳本
# 用法：在 PowerShell 執行
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
# 第一次執行會建立簽章金鑰 keystore\mytvb-release.p12，請務必備份該檔與密碼。

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
Set-Location $Root

# ---- 1. 找 JDK（優先用 Android Studio 內建的 JBR）----
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )
    $jbr = $candidates | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw "找不到 JDK。請先安裝 Android Studio，或設定 JAVA_HOME 指向 JDK 17 以上。" }
    $env:JAVA_HOME = $jbr
}
Write-Host "JAVA_HOME = $env:JAVA_HOME"
$keytool = "$env:JAVA_HOME\bin\keytool.exe"

# ---- 2. 找 Android SDK ----
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-35")) {
    throw "找不到 Android SDK Platform 35（$env:ANDROID_HOME）。請在 Android Studio 的 SDK Manager 安裝 Android 15 (API 35)。"
}
$sdkDir = $env:ANDROID_HOME -replace '\\', '/'
Set-Content -Path "$Root\local.properties" -Value "sdk.dir=$sdkDir" -Encoding ASCII
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"

# ---- 3. 簽章金鑰 ----
$ksDir = Join-Path $Root "keystore"
$ks = Join-Path $ksDir "mytvb-release.p12"
$alias = "mytvb"
$sec = Read-Host "請輸入簽章密碼（第一次執行會用這組密碼建立金鑰，至少 6 個字元）" -AsSecureString
$pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
if ($pw.Length -lt 6) { throw "密碼至少 6 個字元。" }

if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
    Write-Host "建立新的簽章金鑰：$ks"
    & $keytool -genkeypair -keystore $ks -storetype PKCS12 -alias $alias `
        -keyalg RSA -keysize 4096 -validity 36500 `
        -dname "CN=MyTVB BTR Hardened, O=Personal, C=TW" `
        -storepass $pw -keypass $pw
    if ($LASTEXITCODE -ne 0) { throw "keytool 建立金鑰失敗。" }
    Write-Host ""
    Write-Host "!!! 請立刻把 $ks 和密碼備份到安全的地方。弄丟就無法覆蓋更新，只能解除安裝重裝。" -ForegroundColor Yellow
    Write-Host ""
}

$env:RELEASE_STORE_FILE = $ks
$env:RELEASE_STORE_PASSWORD = $pw
$env:RELEASE_KEY_ALIAS = $alias
$env:RELEASE_KEY_PASSWORD = $pw

# ---- 4. 編譯 ----
& "$Root\gradlew.bat" :app:assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { throw "編譯失敗。請把上面的錯誤訊息整段貼給 Claude。" }

# ---- 5. 收集 APK ----
$out = Join-Path $Root "output"
New-Item -ItemType Directory -Force -Path $out | Out-Null
Get-ChildItem "$Root\app\build\outputs\apk\release\*.apk" | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $out "MyTVB-BTR-hardened.apk") -Force
}
Write-Host ""
Write-Host "完成：$out\MyTVB-BTR-hardened.apk" -ForegroundColor Green
Write-Host "安裝到電視：adb connect <電視IP>:5555 ； adb install -r `"$out\MyTVB-BTR-hardened.apk`""
