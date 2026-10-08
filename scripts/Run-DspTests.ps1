param(
    [string]$Serial,
    [string]$BaselineDirectory,
    [string]$BaselineRevision,
    [string[]]$TestNames,
    [switch]$Scalar
)
$ErrorActionPreference = 'Continue'
$workspace = Split-Path $PSScriptRoot -Parent
Push-Location $workspace
try {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    $gradle = Get-Content 'app/build.gradle.kts' -Raw
    $ndkVersion = [regex]::Match($gradle, 'ndkVersion\s*=\s*"([^"]+)"').Groups[1].Value
    $compilerDirectory = Join-Path $sdk "ndk\$ndkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin"
    if (!(Test-Path $adb) -or !(Test-Path (Join-Path $compilerDirectory 'clang++.exe'))) {
        throw 'Android SDK platform-tools and the project NDK must be installed'
    }
    if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    & .\gradlew.bat :app:generateDspProfiles --console plain -q
    if ($LASTEXITCODE) { throw 'DSP profile generation failed' }
    if (!$Serial) {
        $devices = @(& $adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        if ($devices.Count -ne 1) { throw 'Connect one Android device or specify -Serial' }
        $Serial = $devices[0]
    }
    $abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
    $target = switch ($abi) {
        'arm64-v8a' { 'aarch64-linux-android26' }
        'armeabi-v7a' { 'armv7a-linux-androideabi26' }
        'x86_64' { 'x86_64-linux-android26' }
        'x86' { 'i686-linux-android26' }
        default { throw "Unsupported device ABI: $abi" }
    }
    if ($BaselineRevision) {
        if ($BaselineDirectory) { throw 'Specify either -BaselineRevision or -BaselineDirectory' }
        $BaselineDirectory = Join-Path $workspace 'app\build\dsp-baseline'
        New-Item $BaselineDirectory -ItemType Directory -Force -ErrorAction Stop | Out-Null
        $files = & git ls-tree -r --name-only $BaselineRevision -- app/src/main/cpp
        if ($LASTEXITCODE) { throw 'Cannot read baseline revision' }
        foreach ($file in $files) {
            if ($file -notmatch '^app/src/main/cpp/[^/]+\.(cpp|h)$') { continue }
            $content = & git show "${BaselineRevision}:$file"
            if ($LASTEXITCODE) { throw "Cannot read baseline file: $file" }
            [IO.File]::WriteAllText((Join-Path $BaselineDirectory (Split-Path $file -Leaf)), ($content -join "`n") + "`n")
        }
    }
    $source = if ($BaselineDirectory) { (Resolve-Path $BaselineDirectory -ErrorAction Stop).Path } else { Join-Path $workspace 'app\src\main\cpp' }
    $output = Join-Path $workspace 'app\build\dsp-tests'
    New-Item $output -ItemType Directory -Force -ErrorAction Stop | Out-Null
    $tests = if ($BaselineDirectory) { @('demod_interference_test', 'dsp_benchmark') } else {
        @('anti_alias_test', 'filter_bandwidth_test', 'am_demodulator_test', 'nfm_demodulator_test', 'ssb_demodulator_test',
          'wfm_stereo_test', 'tuning_continuity_test', 'audio_agc_test', 'squelch_test',
          'rf_gain_controller_test', 'audio_queue_policy_test', 'hf_tuning_test', 'demod_interference_test', 'dsp_benchmark')
    }
    if ($TestNames) {
        foreach ($name in $TestNames) { if ($name -notin $tests) { throw "Unknown test: $name" } }
        $tests = $TestNames
    }
    foreach ($test in $tests) {
        $flags = @("--target=$target", '-O3', '-UNDEBUG', '-I', $source, '-I', "$workspace\app\build\generated\dsp\cpp")
        if ($BaselineDirectory) { $flags += '-DDSP_BASELINE' }
        if ($Scalar) { $flags += '-DDSP_SCALAR' }
        if ($test -eq 'hf_tuning_test') {
            $compiler = Join-Path $compilerDirectory 'clang.exe'
            $flags += @('-std=c11', '-I', "$workspace\app\src\main\cpp\third_party\rtl_sdr_blog\include", "$workspace\app\src\test\cpp\$test.c", '-lm')
        } else {
            $compiler = Join-Path $compilerDirectory 'clang++.exe'
            $flags += @('-std=c++17', '-static-libstdc++', "$workspace\app\src\test\cpp\$test.cpp")
            if ($test -match '^(am_demodulator|wfm_stereo|tuning_continuity|demod_interference|dsp_benchmark)_test$' -or $test -eq 'dsp_benchmark') {
                $flags += @("$source\am_demodulator.cpp", "$source\wfm_demodulator.cpp")
            }
        }
        $binary = Join-Path $output $test
        & $compiler @flags -o $binary
        if ($LASTEXITCODE) { throw "Compilation failed: $test" }
        $remote = "/data/local/tmp/radiola_$test"
        & $adb -s $Serial push $binary $remote 2>&1 | Out-Null
        if ($LASTEXITCODE) { throw "Push failed: $test" }
        & $adb -s $Serial shell chmod 755 $remote
        if ($LASTEXITCODE) { throw "chmod failed: $test" }
        Write-Host "--- $test ($abi) ---"
        & $adb -s $Serial shell $remote
        if ($LASTEXITCODE) { throw "Test failed: $test" }
        & $adb -s $Serial shell rm $remote
    }
} finally { Pop-Location }
