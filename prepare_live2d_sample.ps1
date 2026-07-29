param(
    [switch]$AcceptLive2DTerms,
    [string]$SdkRoot = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

if (-not $AcceptLive2DTerms) {
    throw @"
Preparing the Hiyori sample requires accepting Live2D's Free Material License and
the Terms of Use for Live2D Cubism Sample Data:
https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html
https://www.live2d.com/en/learn/sample/model-terms/

Read the terms, then run this script again with -AcceptLive2DTerms.
"@
}

if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $sdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "app") -Directory |
        Where-Object { $_.Name -like "CubismSdkForJava-*" } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $sdk) {
        throw "No app/CubismSdkForJava-* directory was found. Download the Java SDK from Live2D first."
    }
    $SdkRoot = $sdk.FullName
}

$source = Join-Path ([System.IO.Path]::GetFullPath($SdkRoot)) "Sample\src\main\assets\Hiyori"
$assetRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "app\src\main\assets"))
$target = [System.IO.Path]::GetFullPath((Join-Path $assetRoot "Hiyori"))
if (-not $target.StartsWith($assetRoot + [System.IO.Path]::DirectorySeparatorChar)) {
    throw "Refusing to prepare a sample outside the app assets directory."
}
if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw "The official Hiyori sample was not found at: $source"
}
if ((Test-Path -LiteralPath $target) -and -not $Force) {
    throw "The target already exists: $target. Use -Force only if you intend to replace it."
}
if (Test-Path -LiteralPath $target) {
    Remove-Item -LiteralPath $target -Recurse -Force
}

Copy-Item -LiteralPath $source -Destination $target -Recurse
Write-Output "Prepared the official Live2D Hiyori sample at: $target"
Write-Output "The sample remains ignored by Git; distribute applications only as permitted by Live2D's terms."
