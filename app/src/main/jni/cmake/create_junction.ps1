# SPDX-License-Identifier: GPL-3.0-or-later
param(
    [Parameter(Mandatory = $true)][string]$LinkPath,
    [Parameter(Mandatory = $true)][string]$TargetPath
)
$ErrorActionPreference = 'Stop'
$target = (Resolve-Path -LiteralPath $TargetPath).Path
if (!(Test-Path -LiteralPath $target -PathType Container)) {
    throw "Plugin target is not a directory: $target"
}
if (!(Test-Path -LiteralPath $LinkPath)) {
    New-Item -ItemType Junction -Path $LinkPath -Target $target | Out-Null
}
$link = Get-Item -LiteralPath $LinkPath -Force
if ($link.LinkType -notin @('Junction', 'SymbolicLink')) {
    throw "Refusing a plugin path that is not a link: $LinkPath"
}
$actual = [string]$link.Target
if (![IO.Path]::IsPathRooted($actual)) {
    $actual = Join-Path (Split-Path -Parent $LinkPath) $actual
}
$actual = (Resolve-Path -LiteralPath $actual).Path
if (![string]::Equals($actual, $target, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Plugin link points to a different target: $LinkPath"
}
