# [btr-hardening] 產生應用內更新驗證器的裝置測試用安裝檔（ApkArchiveProbeTest 使用）。
# 用兩把一次性的測試金鑰重新簽章現有的正式版 APK，做出：正常、換金鑰、多簽章者、金鑰輪替、
# 沒簽章、內容被竄改（簽章區塊還在但對不上內容）六種安裝檔。
# 測試金鑰只存在 build\updater-fixtures，輸出在 app\src\androidTest\assets\updater（兩者都不進 git）。
#
# 用法：powershell -ExecutionPolicy Bypass -File .\tools\updater-fixtures\make-fixtures.ps1 [-BaseApk <apk>]

param([string]$BaseApk = "output\Lanes-TV.apk")

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } -Descending | Select-Object -First 1
$apksigner = Join-Path $bt.FullName "apksigner.bat"
$zipalign = Join-Path $bt.FullName "zipalign.exe"
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "$env:ProgramFiles\Android\Android Studio\jbr" }
$java = Join-Path $javaHome "bin\java.exe"
$keytool = Join-Path $javaHome "bin\keytool.exe"
$tool = Join-Path $PSScriptRoot "FixtureTool.java"

if (-not (Test-Path $BaseApk)) { throw "找不到 $BaseApk，請先用 build.ps1 編出正式版。" }

$work = Join-Path $Root "build\updater-fixtures"
$out = Join-Path $Root "app\src\androidTest\assets\updater"
Remove-Item -Recurse -Force $work, $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work, $out | Out-Null

function Invoke-Checked([string]$exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$exe 失敗（exit $LASTEXITCODE）：$($argv -join ' ')" }
}

# 一次性測試金鑰，密碼固定也無妨（不用於任何正式簽章）
$pass = "fixture-only"
foreach ($k in "a", "b") {
    Invoke-Checked $keytool @("-genkeypair", "-keystore", "$work\$k.p12", "-storetype", "PKCS12", "-alias", $k,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Lanes-TV Updater Fixture $k",
        "-storepass", $pass, "-keypass", $pass)
}
$ksA = @("--ks", "$work\a.p12", "--ks-pass", "pass:$pass")
$ksB = @("--ks", "$work\b.p12", "--ks-pass", "pass:$pass")

Invoke-Checked $java @($tool, "strip", $BaseApk, "$work\unsigned-raw.apk")
Invoke-Checked $zipalign @("-p", "-f", "4", "$work\unsigned-raw.apk", "$out\unsigned.apk")

Invoke-Checked $apksigner (@("sign") + $ksA + @("--out", "$out\valid.apk", "$out\unsigned.apk"))
Invoke-Checked $apksigner (@("sign") + $ksB + @("--out", "$out\wrongkey.apk", "$out\unsigned.apk"))
Invoke-Checked $apksigner (@("sign") + $ksA + @("--next-signer") + $ksB +
    @("--v3-signing-enabled", "false", "--out", "$out\multi.apk", "$out\unsigned.apk"))
Invoke-Checked $apksigner (@("rotate", "--out", "$work\lineage.bin", "--old-signer") + $ksA + @("--new-signer") + $ksB)
Invoke-Checked $apksigner (@("sign") + $ksA + @("--next-signer") + $ksB +
    @("--lineage", "$work\lineage.bin", "--rotation-min-sdk-version", "28", "--out", "$out\rotated.apk", "$out\unsigned.apk"))
Invoke-Checked $java @($tool, "tamper", "$out\valid.apk", "$out\tampered.apk")
Remove-Item "$out\*.idsig" -ErrorAction SilentlyContinue

# 竄改過的安裝檔必須讓 apksigner 驗證失敗，但憑證仍讀得出來
# （PowerShell 5.1 在 Stop 模式下會把原生程式的 stderr 當成例外，這兩段先改成 Continue）
$ErrorActionPreference = "Continue"
& $apksigner verify "$out\tampered.apk" 2>&1 | Out-Null
$tamperedExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($tamperedExit -eq 0) { throw "tampered.apk 竟然通過驗證，測試檔無效。" }

function Get-CertSha([string]$apk) {
    $ErrorActionPreference = "Continue"
    $line = & $apksigner verify --print-certs $apk 2>&1 | ForEach-Object { "$_" } |
        Select-String "Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})"
    if (-not $line) { throw "讀不到 $apk 的憑證" }
    return $line.Matches[0].Groups[1].Value
}
$shaA = Get-CertSha "$out\valid.apk"
$shaB = Get-CertSha "$out\wrongkey.apk"
"{`"signerA`":`"$shaA`",`"signerB`":`"$shaB`"}" | Set-Content -Encoding ASCII "$out\fixtures.json"

Get-ChildItem $out | Format-Table Name, Length -AutoSize
Write-Host "signerA=$shaA"
Write-Host "signerB=$shaB"
