# [btr-hardening] 發佈 Lanes-TV 新版本到 GitHub Releases（App 內「檢查更新」讀的就是這裡）。
#
# 流程：
#   1. 檢查 output\Lanes-TV.apk：簽章必須通過、只有一個簽章者、而且是正式金鑰（和 App 內的指紋相同）；
#      不可為 debug 版；versionName 必須是 X.Y.Z
#   2. 產生 Lanes-TV-vX.Y.Z.apk 與 update.json，放在 output\release-vX.Y.Z\
#   3. 用 git 已存的 GitHub 登入（git credential fill）建立草稿 release、上傳兩個檔案、
#      確認上傳結果後才正式發佈。權杖只留在記憶體，不寫入任何檔案、不印出來。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\tools\publish-release.ps1 -Notes "修正…"
#   加 -DryRun 只做第 1、2 步（不連網）

param(
    [string]$Apk = "output\Lanes-TV.apk",
    [string]$Notes = "",
    [string]$NotesFile = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

# ---- 設定從 App 原始碼讀，避免兩邊不一致 ----
$configKt = Get-Content -Raw -Encoding UTF8 "app\src\main\java\com\mytvb\core\common\update\UpdateConfig.kt"
function Get-KtConst([string]$name) {
    if ($configKt -notmatch "const val $name\s*=\s*`"([^`"]+)`"") { throw "UpdateConfig.kt 找不到 $name" }
    return $Matches[1]
}
$owner = Get-KtConst "OWNER"
$repo = Get-KtConst "REPO"
if ($configKt -notmatch 'RELEASE_CERT_SHA256\s*=\s*"([0-9a-f]{64})"') { throw "UpdateConfig.kt 找不到 RELEASE_CERT_SHA256" }
$pin = $Matches[1]
$expectedPackage = "com.mytvb.btr"

# ---- 工具 ----
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } -Descending | Select-Object -First 1
$apksigner = Join-Path $bt.FullName "apksigner.bat"
$aapt2 = Join-Path $bt.FullName "aapt2.exe"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr" }

function Invoke-Native([string]$exe, [string[]]$argv) {
    $ErrorActionPreference = "Continue"
    $out = & $exe @argv 2>&1 | ForEach-Object { "$_" }
    return @{ Code = $LASTEXITCODE; Out = $out }
}

# ---- 1. 檢查安裝檔 ----
if (-not (Test-Path $Apk)) { throw "找不到 $Apk，請先執行 build.ps1。" }
$Apk = (Resolve-Path $Apk).Path

$v = Invoke-Native $apksigner @("verify", "--print-certs", $Apk)
if ($v.Code -ne 0) { throw "apksigner 驗證失敗：`n$($v.Out -join "`n")" }
$signers = @($v.Out | Select-String "^Signer #(\d+) certificate SHA-256 digest: ([0-9a-f]{64})$")
if ($signers.Count -ne 1) { throw "必須剛好一個簽章者，實際 $($signers.Count) 個。" }
$certSha = $signers[0].Matches[0].Groups[2].Value
if ($certSha -ne $pin) { throw "簽章不是正式金鑰（$certSha），不能發佈。debug 版或其他金鑰簽的安裝檔，電視上會被更新器拒絕。" }
$lineage = Invoke-Native $apksigner @("lineage", "--in", $Apk, "--print-certs")
# 沒有 lineage 時 apksigner 只印「does not contain a valid lineage」；有兩個以上簽章者就是輪替過
$lineageSigners = @($lineage.Out | Select-String "^Signer #[0-9]+ in lineage certificate SHA-256").Count
# 沒有 v3 區塊（只有 v1/v2，正式版目前就是這樣）或 v3 沒有 lineage，都代表沒輪替過
$noLineage = [bool]($lineage.Out | Select-String "does not contain a valid lineage|does not contain a valid V3")
if (-not $noLineage -and $lineageSigners -eq 0) { throw "無法確認安裝檔有沒有金鑰輪替（apksigner lineage 輸出不如預期）：`n$($lineage.Out -join "`n")" }
if ($lineageSigners -ge 2) { throw "安裝檔帶有金鑰輪替紀錄，更新器不接受。" }

$b = Invoke-Native $aapt2 @("dump", "badging", $Apk)
if ($b.Code -ne 0) { throw "aapt2 讀取失敗：`n$($b.Out -join "`n")" }
$badging = $b.Out -join "`n"
if ($badging -notmatch "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") { throw "讀不到 package 資訊" }
$package = $Matches[1]; $versionCode = [long]$Matches[2]; $versionName = $Matches[3]
if ($badging -notmatch "(?m)^(min)?[sS]dkVersion:'(\d+)'") { throw "讀不到 minSdkVersion" }
$minSdk = [int]$Matches[2]
if ($badging -match "application-debuggable") { throw "這是 debug 版，不能發佈。" }
if ($package -ne $expectedPackage) { throw "套件名稱是 $package，應為 $expectedPackage（是不是用了 -PapplicationId？）" }
# 不允許前導零（1.02.0）：App 會把 tag 解析成數字再比對 versionName，前導零會對不上
if ($versionName -notmatch '^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})$') { throw "versionName「$versionName」不是 X.Y.Z（數字不可有前導零）。" }

$tag = "v$versionName"
$apkName = "Lanes-TV-v$versionName.apk"
Write-Host "安裝檔：$package $versionName（versionCode $versionCode，minSdk $minSdk），正式金鑰簽章 OK" -ForegroundColor Green

# ---- 2. 產生發佈檔 ----
$relDir = Join-Path $Root "output\release-$tag"
New-Item -ItemType Directory -Force $relDir | Out-Null
$relApk = Join-Path $relDir $apkName
Copy-Item $Apk $relApk -Force
$size = (Get-Item $relApk).Length
# 和 UpdateConfig.MAX_APK_BYTES 相同；超過的話 App 會拒絕這一版
if ($size -gt 128MB) { throw "安裝檔 $size bytes 超過更新器上限 128 MiB。" }
$sha = (Get-FileHash -Algorithm SHA256 $relApk).Hash.ToLowerInvariant()
$meta = [ordered]@{
    schema      = 1
    packageName = $package
    versionCode = $versionCode
    versionName = $versionName
    apkName     = $apkName
    size        = $size
    sha256      = $sha
    minSdk      = $minSdk
}
$metaPath = Join-Path $relDir "update.json"
[IO.File]::WriteAllText($metaPath, ($meta | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
Write-Host "已產生：$relApk"
Write-Host "        $metaPath"

if ($NotesFile) { $Notes = Get-Content -Raw -Encoding UTF8 $NotesFile }
if ($DryRun) { Write-Host "DryRun：不上傳。tag = $tag"; return }
if (-not $Notes.Trim()) { throw "請用 -Notes 或 -NotesFile 提供更新內容（會顯示在電視的更新對話框）。" }

# ---- 3. 上傳到 GitHub ----
$head = (git rev-parse HEAD).Trim()
$dirty = git status --porcelain
if ($dirty) { throw "工作目錄有未 commit 的修改，請先 commit 並 push，發佈才對得上原始碼。" }
git fetch origin main --quiet
if ($LASTEXITCODE -ne 0) { throw "git fetch origin main 失敗，請檢查網路。" }
git merge-base --is-ancestor $head origin/main
if ($LASTEXITCODE -ne 0) { throw "HEAD（$head）還沒 push 到 origin/main，請先 push。" }
# 遠端已有同名 tag 時，GitHub 會忽略 target_commitish，release 會指到那個 tag 的舊 commit
$remoteTag = git ls-remote --tags origin "refs/tags/$tag"
if ($LASTEXITCODE -ne 0) { throw "無法查詢 origin 的 tag（git ls-remote 失敗），請檢查網路。" }
if ($remoteTag) { throw "origin 已經有 tag $tag，請先遞增版本（或確認後手動刪除那個 tag）。" }

# 從 git 的認證管理員取得權杖（只在記憶體）
$credOut = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$token = ($credOut | Where-Object { $_ -like "password=*" } | Select-Object -First 1) -replace '^password=', ''
if (-not $token) { throw "git 認證管理員裡沒有 github.com 的登入，請先在這台電腦 git push 一次。" }

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$headers = @{
    Authorization          = "Bearer $token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "Lanes-TV-publish"
}
$api = "https://api.github.com/repos/$owner/$repo"

try {
    try {
        Invoke-RestMethod -Headers $headers -Uri "$api/releases/tags/$tag" | Out-Null
        throw "GitHub 上已經有 $tag，請先遞增版本。"
    } catch [System.Net.WebException] {
        if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
    }

    # 先建草稿：App 會略過草稿，檔案沒傳完前不會有人看到不完整的版本
    $body = @{
        tag_name         = $tag
        target_commitish = $head
        name             = "Lanes-TV $versionName"
        body             = $Notes.Trim()
        draft            = $true
        prerelease       = $false
    } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Post -Headers $headers -Uri "$api/releases" -ContentType "application/json; charset=utf-8" `
        -Body ([Text.Encoding]::UTF8.GetBytes($body))
    Write-Host "已建立草稿 release $($release.id)"

    $uploadBase = "https://uploads.github.com/repos/$owner/$repo/releases/$($release.id)/assets"
    foreach ($f in @(@{ Path = $relApk; Type = "application/vnd.android.package-archive" }, @{ Path = $metaPath; Type = "application/json" })) {
        $name = Split-Path -Leaf $f.Path
        Invoke-RestMethod -Method Post -Headers $headers -Uri "$uploadBase`?name=$name" -ContentType $f.Type -InFile $f.Path | Out-Null
        Write-Host "已上傳 $name"
    }

    # 確認 GitHub 上的檔案大小正確才發佈
    $check = Invoke-RestMethod -Headers $headers -Uri "$api/releases/$($release.id)"
    $assets = @{}
    foreach ($a in $check.assets) { $assets[$a.name] = $a.size }
    if ($assets[$apkName] -ne $size) { throw "GitHub 上的 $apkName 大小不符，release 保持草稿，請到網頁檢查。" }
    if ($assets["update.json"] -ne (Get-Item $metaPath).Length) { throw "GitHub 上的 update.json 大小不符，release 保持草稿。" }

    Invoke-RestMethod -Method Patch -Headers $headers -Uri "$api/releases/$($release.id)" -ContentType "application/json" `
        -Body (@{ draft = $false } | ConvertTo-Json) | Out-Null
    Write-Host "已發佈 $tag：https://github.com/$owner/$repo/releases/tag/$tag" -ForegroundColor Green
} finally {
    $token = $null
    $headers = $null
}
