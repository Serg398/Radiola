$ErrorActionPreference = 'Stop'
$expectedSignerSha256 = 'c0898d76f6da0e25016c046d3b8956abe6b005f0496ae0e6202ffd4c6338e883'
$root = Split-Path $PSScriptRoot -Parent
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and
        (Test-Path (Join-Path $_.FullName 'apksigner.bat')) -and
        (Test-Path (Join-Path $_.FullName 'aapt.exe')) } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Install Android SDK Build Tools with apksigner and aapt.' }
if (!$env:JAVA_HOME) {
    $studioJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
    if (Test-Path $studioJdk) { $env:JAVA_HOME = $studioJdk }
}

Push-Location $root
try {
    $version = ConvertFrom-StringData (Get-Content version.properties -Raw)
    $destination = Join-Path $root "app/build/distributions/radiola-$($version.versionName)"
    if (Test-Path $destination) { throw "Release artifacts already exist at $destination." }

    & .\gradlew.bat :app:verifyReleaseSigning :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Release build or checks failed.' }

    $apk = 'app/build/outputs/apk/release/app-release.apk'
    $signature = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signers = @($signature | Select-String '^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$')
    if ($signers.Count -ne 1 -or $signers[0].Matches[0].Groups[1].Value -ine $ExpectedSignerSha256) {
        throw 'APK signer differs from the expected release certificate.'
    }
    $badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK metadata.' }
    $expected = "package: name='com.serg398.radiola' versionCode='$($version.versionCode)' versionName='$($version.versionName)'"
    if (!($badging | Where-Object { $_.StartsWith($expected) })) {
        throw 'APK application ID or version differs from the release configuration.'
    }
    if ($badging -contains 'application-debuggable') { throw 'Release APK is debuggable.' }

    $name = "radiola-$($version.versionName).apk"
    $sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    New-Item -ItemType Directory -Path $destination | Out-Null
    Copy-Item -LiteralPath $apk -Destination (Join-Path $destination $name)
    "$sha256  $name" | Set-Content -Encoding ascii (Join-Path $destination 'SHA256SUMS.txt')
    $signature | Set-Content -Encoding utf8 (Join-Path $destination 'signature.txt')
    Write-Output $destination
} finally {
    Pop-Location
}
