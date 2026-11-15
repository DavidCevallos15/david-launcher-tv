<#
.SYNOPSIS
  Prepara (una sola vez) las descargas públicas: llave de firma, página de descarga y primera versión.

.DESCRIPTION
  Android solo instala una actualización encima si está firmada con la MISMA llave. Este script:
    1. Genera release.p12 (RSA 4096, válida ~27 años) en Documentos\david-launcher-tv-firma\
    2. Guarda la contraseña en LEEME-FIRMA.txt junto al archivo
    3. Sube la llave y la contraseña como secretos cifrados del repo (gh secret set)
    4. Activa GitHub Pages (la página con el QR: https://www.davidcevallos.me/david-launcher-tv/)
    5. Publica la versión indicada (por defecto v0.6.0) con el workflow "release"
  La llave nunca se sube al código.

  Requisitos: GitHub CLI con sesión iniciada (gh auth status) y un JDK (keytool).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\setup-release-signing.ps1
#>
param(
    [string]$Repo = 'DavidCevallos15/david-launcher-tv',
    [string]$Dir = "$env:USERPROFILE\Documents\david-launcher-tv-firma",
    [string]$Version = 'v0.6.0'
)
$ErrorActionPreference = 'Stop'

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
    $keytool = Get-ChildItem 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Android\Android Studio\jbr' `
        -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $keytool) { throw 'No encontré keytool. Instala un JDK (winget install EclipseAdoptium.Temurin.21.JDK).' }
gh auth status *> $null; if ($LASTEXITCODE -ne 0) { throw 'Inicia sesión primero: gh auth login' }

$store = Join-Path $Dir 'release.p12'
$alias = 'davidlaunchertv'
if (Test-Path $store) {
    Write-Host "Ya existe $store. No se regenera (cambiar la llave rompe las actualizaciones)." -ForegroundColor Yellow
    $pass = (Select-String -Path (Join-Path $Dir 'LEEME-FIRMA.txt') -Pattern '^Clave: (.+)$').Matches[0].Groups[1].Value
} else {
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pass = ([Convert]::ToBase64String($bytes)) -replace '[+/=]', 'x'
    & $keytool -genkeypair -storetype PKCS12 -keystore $store -storepass $pass -keypass $pass -alias $alias `
        -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=David Cevallos, O=David Launcher TV, C=EC' | Out-Null
    @"
LLAVE DE FIRMA - David Launcher TV / TV Remote
Archivo: release.p12 (PKCS12)
Alias: $alias
Clave: $pass

Guarda esta carpeta en un lugar seguro (gestor de contraseñas o USB).
Si se pierde, las próximas versiones no se instalarán encima de las actuales.
No la subas al repositorio.
"@ | Set-Content -Encoding UTF8 (Join-Path $Dir 'LEEME-FIRMA.txt')
    Write-Host "Llave creada en $Dir" -ForegroundColor Green
}

[Convert]::ToBase64String([IO.File]::ReadAllBytes($store)) | gh secret set RELEASE_KEYSTORE_BASE64 -R $Repo
$pass  | gh secret set RELEASE_KEYSTORE_PASSWORD -R $Repo
$pass  | gh secret set RELEASE_KEY_PASSWORD -R $Repo
$alias | gh secret set RELEASE_KEY_ALIAS -R $Repo
Write-Host "`nSecretos guardados en $Repo :" -ForegroundColor Green
gh secret list -R $Repo

# Página de descarga (GitHub Pages desde Actions). Si ya estaba activa, GitHub responde 409: se ignora.
gh api -X POST "repos/$Repo/pages" -f build_type=workflow *> $null
gh api -X PUT "repos/$Repo/pages" -f build_type=workflow *> $null
gh workflow run pages -R $Repo
Write-Host "Página de descarga: https://www.davidcevallos.me/david-launcher-tv/" -ForegroundColor Green

# Primera versión firmada
gh workflow run release -R $Repo -f tag=$Version
Write-Host "`nPublicando $Version... sigue el progreso en: https://github.com/$Repo/actions" -ForegroundColor Cyan
Write-Host 'Próximas versiones: git tag vX.Y.Z; git push origin vX.Y.Z' -ForegroundColor Cyan
