# One-shot: publish the app self-contained, then compile the Inno Setup
# installer. Produces windows\installer\Output\CoulombMpptSetup-<ver>.exe.
#
# Prereqs:
#   • .NET SDK (dotnet on PATH, or adjust $dotnet below)
#   • Inno Setup 6 (ISCC.exe). Install once with:  winget install JRSoftware.InnoSetup
#
# Usage:  powershell -ExecutionPolicy Bypass -File tools\build-installer.ps1
$ErrorActionPreference = "Stop"

$root    = Split-Path -Parent $PSScriptRoot          # ...\windows
$proj    = Join-Path $root "src\CoulombMppt\CoulombMppt.csproj"
$pub     = Join-Path $root "publish\win-x64"
$iss     = Join-Path $root "installer\CoulombMppt.iss"

$dotnet = "dotnet"
if (Test-Path "C:\Program Files\dotnet\dotnet.exe") { $dotnet = "C:\Program Files\dotnet\dotnet.exe" }

Write-Host "==> Regenerating app icon"
& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "gen-icon.ps1")

Write-Host "==> Publishing self-contained (win-x64)"
& $dotnet publish $proj -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=false -p:DebugType=none -o $pub --nologo
if ($LASTEXITCODE -ne 0) { throw "publish failed ($LASTEXITCODE)" }

# Locate the Inno Setup compiler.
$iscc = $null
foreach ($c in @(
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe",
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe")) {
    if (Test-Path $c) { $iscc = $c; break }
}
if (-not $iscc) { $iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source }
if (-not $iscc) {
    throw "ISCC.exe (Inno Setup 6) not found. Install with: winget install JRSoftware.InnoSetup"
}

Write-Host "==> Compiling installer with $iscc"
& $iscc $iss
if ($LASTEXITCODE -ne 0) { throw "ISCC failed ($LASTEXITCODE)" }

Write-Host "==> Done. Installer in $root\installer\Output"
