# ghidra_headless.ps1 -- run a Ghidra script over the game binary with no GUI.
#
# Ghidra's headless mode is the fastest way to answer "where did this offset move to" after a game
# update. The first run analyses the whole 16 MB binary and takes 10-20 minutes; after that the project
# is cached on disk and re-running a script takes seconds.
#
#   .\tools\ghidra_headless.ps1 -Ghidra "C:\ghidra_11.4" -Script FindOffsets.java
#
# Two things that will waste your evening if nobody tells you:
#
#   1. Run this from PowerShell, not bash. Ghidra's launcher is a .bat and bash mangles the arguments.
#   2. Ghidra truncates script output in its own log. WRITE RESULTS TO A FILE from inside the script --
#      FindOffsets.java does exactly that, into tools\offsets_found.txt.

param(
    [string]$Ghidra  = "C:\ghidra",
    [string]$Client  = "C:\Program Files (x86)\Jagex Launcher\Games\Old School RuneScape\Client\osclient.exe",
    [string]$Project = "$PSScriptRoot\..\build\ghidra",
    [string]$Script  = "FindOffsets.java"
)

$headless = Join-Path $Ghidra "support\analyzeHeadless.bat"
if (-not (Test-Path $headless)) {
    Write-Error "analyzeHeadless.bat not found under $Ghidra -- pass -Ghidra <your ghidra folder>"
    exit 1
}
if (-not (Test-Path $Client)) {
    Write-Error "game binary not found at $Client -- pass -Client <path to osclient.exe>"
    exit 1
}

New-Item -ItemType Directory -Force -Path $Project | Out-Null
$scriptDir = Join-Path $PSScriptRoot "ghidra_scripts"

# -import on the first run creates and analyses the project. On later runs Ghidra notices the program is
# already there and reuses the analysis, which is why this is slow exactly once.
& $headless $Project 0xClient `
    -import $Client `
    -scriptPath $scriptDir `
    -postScript $Script `
    -analysisTimeoutPerFile 3600

Write-Host ""
Write-Host "results (if the script wrote any): tools\offsets_found.txt"
