$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $repoRoot 'app/keystores/waterbench-release-v2.p12'
$encryptedPasswordPath = Join-Path $env:USERPROFILE '.android/waterbench-release-password.dpapi'
$androidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$androidStudioJdk = 'C:/Program Files/Android/Android Studio/jbr'

if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
    $cachedGradleDir = Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-8.9-bin'
    $gradleBat = Get-ChildItem -LiteralPath $cachedGradleDir -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $gradleBat) { throw 'Gradle 8.9 was not found. Install Gradle 8.7 or newer and add it to PATH.' }
    $env:PATH = "$(Split-Path -Parent $gradleBat);$env:PATH"
}
if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath $androidSdk)) {
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
}
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath $androidStudioJdk)) {
    $env:JAVA_HOME = $androidStudioJdk
}

if (-not (Test-Path -LiteralPath $keystore)) {
    throw "Release keystore not found: $keystore"
}
if (-not (Test-Path -LiteralPath $encryptedPasswordPath)) {
    throw "Encrypted release password not found: $encryptedPasswordPath"
}

$securePassword = Get-Content -LiteralPath $encryptedPasswordPath -Raw | ConvertTo-SecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:WATERBENCH_RELEASE_STORE_FILE = $keystore
    $env:WATERBENCH_RELEASE_STORE_PASSWORD = $password
    $env:WATERBENCH_RELEASE_KEY_ALIAS = 'waterbench-release-2026'
    $env:WATERBENCH_RELEASE_KEY_PASSWORD = $password

    Push-Location $repoRoot
    try {
        & gradle assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    Remove-Item Env:WATERBENCH_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:WATERBENCH_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:WATERBENCH_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:WATERBENCH_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable password -ErrorAction SilentlyContinue
}
