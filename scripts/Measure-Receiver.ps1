param(
    [ValidateRange(5, 600)][int]$Seconds = 30,
    [ValidatePattern('^[A-Za-z0-9_.]+$')][string]$PackageName = 'com.serg398.radiola.debug',
    [string]$Label = 'receiver',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../build/measurements')
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$receiverPid = ((& $adb shell pidof $PackageName) -join '').Trim()
if ($LASTEXITCODE -ne 0 -or $receiverPid -notmatch '^\d+$') { throw 'Start one instance of the receiver first.' }
function Read-CpuTicks {
    $raw = ((& $adb shell run-as $PackageName cat "/proc/$receiverPid/stat") -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or !$raw.Contains(')')) { throw 'CPU sampling requires the debuggable application.' }
    $fields = $raw.Substring($raw.LastIndexOf(')') + 2) -split '\s+'
    return ([long]$fields[11] + [long]$fields[12])
}
$clockTicks = ((& $adb shell getconf CLK_TCK) -join '').Trim()
if ($clockTicks -notmatch '^\d+$') { throw 'Cannot determine the device CPU clock tick frequency.' }
$first = Read-CpuTicks
$timer = [Diagnostics.Stopwatch]::StartNew()
Start-Sleep -Seconds $Seconds
$last = Read-CpuTicks
$timer.Stop()
$currentPid = ((& $adb shell pidof $PackageName) -join '').Trim()
if ($currentPid -ne $receiverPid) { throw 'The application restarted during measurement; discard this sample.' }
$memory = (& $adb shell dumpsys meminfo $PackageName) -join "`n"
$battery = (& $adb shell dumpsys battery) -join "`n"
$pss = [regex]::Match($memory, 'TOTAL PSS:\s*(\d+)')
$rss = [regex]::Match($memory, 'TOTAL RSS:\s*(\d+)')
$temperature = [regex]::Match($battery, 'temperature:\s*(-?\d+)')
$cpuMs = ($last - $first) * 1000.0 / [long]$clockTicks
$result = [ordered]@{
    utc = [DateTime]::UtcNow.ToString('o'); label = $Label; package = $PackageName; pid = $receiverPid
    elapsedMs = [math]::Round($timer.Elapsed.TotalMilliseconds, 1)
    cpuMs = [math]::Round($cpuMs, 1)
    cpuOneCorePercent = [math]::Round($cpuMs / $timer.Elapsed.TotalMilliseconds * 100, 2)
    pssKb = if ($pss.Success) { [long]$pss.Groups[1].Value } else { $null }
    rssKb = if ($rss.Success) { [long]$rss.Groups[1].Value } else { $null }
    batteryTemperatureC = if ($temperature.Success) { [int]$temperature.Groups[1].Value / 10.0 } else { $null }
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$destination = Join-Path $OutputDirectory ("receiver-{0}.json" -f [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
$result | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath $destination
Write-Output $destination
$result | ConvertTo-Json
