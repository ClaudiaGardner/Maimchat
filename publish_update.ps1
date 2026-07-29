param(
    [ValidateSet("debug", "release")]
    [string]$BuildType = "debug",
    [int]$VersionCode = [int](Get-Date -Format "yyMMddHH"),
    [string]$VersionName = "",
    [string]$Notes = "Maimchat Android update",
    [string]$ResourceSource = "",
    [string]$ResourceId = "",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "dist\updates")
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $VersionName = "0.2.0-dev.$VersionCode"
}
if ($BuildType -eq "release" -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "signing.properties"))) {
    throw "Release OTA requires signing.properties and a stable release keystore."
}

$updateDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $updateDirectory | Out-Null

$env:MAIMCHAT_VERSION_CODE = $VersionCode.ToString()
$env:MAIMCHAT_VERSION_NAME = $VersionName

$gradleTask = if ($BuildType -eq "release") { ":app:assembleRelease" } else { ":app:assembleDebug" }
& (Join-Path $PSScriptRoot "gradlew.bat") $gradleTask
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apkDirectory = Join-Path $PSScriptRoot "app\build\outputs\apk\$BuildType"
$sourceApk = Get-ChildItem -LiteralPath $apkDirectory -Filter "*.apk" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $sourceApk) {
    throw "No APK found in $apkDirectory"
}

$apkName = "maimchat-$VersionCode-$BuildType.apk"
$publishedApk = Join-Path $updateDirectory $apkName
Copy-Item -LiteralPath $sourceApk.FullName -Destination $publishedApk -Force
$sha256 = (Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash.ToLowerInvariant()

$resources = @()
if (-not [string]::IsNullOrWhiteSpace($ResourceSource)) {
    if ([string]::IsNullOrWhiteSpace($ResourceId)) {
        throw "ResourceId is required when ResourceSource is provided."
    }
    if ($ResourceId -notmatch "^[A-Za-z0-9._-]{1,64}$") {
        throw "ResourceId must contain only letters, numbers, dots, underscores, or hyphens."
    }

    $resourcePath = if ([System.IO.Path]::IsPathRooted($ResourceSource)) {
        [System.IO.Path]::GetFullPath($ResourceSource)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $ResourceSource))
    }
    if (-not (Test-Path -LiteralPath $resourcePath -PathType Container)) {
        throw "ResourceSource directory does not exist: $resourcePath"
    }

    $resourceName = "$($ResourceId.ToLowerInvariant())-resources-$VersionCode.zip"
    $resourceArchive = Join-Path $updateDirectory $resourceName
    if (Test-Path -LiteralPath $resourceArchive) {
        Remove-Item -LiteralPath $resourceArchive -Force
    }
    Compress-Archive -Path (Join-Path $resourcePath "*") -DestinationPath $resourceArchive
    $resourceSha256 = (
        Get-FileHash -LiteralPath $resourceArchive -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $resources += [ordered]@{
        id = $ResourceId
        version = $VersionCode
        url = $resourceName
        sha256 = $resourceSha256
    }
}

$manifest = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    apkUrl = $apkName
    sha256 = $sha256
    notes = $Notes
    mandatory = $false
    resources = $resources
}
$manifestPath = Join-Path $updateDirectory "manifest.json"
$manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8

Write-Output "Published APK: $publishedApk"
Write-Output "Manifest:      $manifestPath"
Write-Output "SHA-256:       $sha256"
