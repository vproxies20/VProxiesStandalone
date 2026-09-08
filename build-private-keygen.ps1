param(
  [Parameter(Mandatory = $true)]
  [string]$PrivateKeyPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path $PrivateKeyPath -PathType Leaf)) { throw "Private key not found: $PrivateKeyPath" }

$output = Join-Path $root 'private-artifacts\VProxiesSA-License-Generator'
$zip = Join-Path $root 'private-artifacts\VProxiesSA-License-Generator.zip'
Remove-Item $output -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $zip -Force -ErrorAction SilentlyContinue

dotnet publish (Join-Path $root 'tools\VProxiesSA.Keygen\VProxiesSA.Keygen.csproj') -c Release -r win-x64 --self-contained true -o $output `
  /p:PublishSingleFile=true /p:IncludeNativeLibrariesForSelfExtract=true /p:DebugType=None /p:DebugSymbols=false
if ($LASTEXITCODE -ne 0) { throw "Key generator publish failed with exit code $LASTEXITCODE." }

Copy-Item $PrivateKeyPath (Join-Path $output 'VProxiesSA-private-key.pem') -Force
Set-Content (Join-Path $output 'KEEP-PRIVATE.txt') "PRIVATE: Do not upload this folder, executable, or PEM key to GitHub and do not send them to customers." -Encoding utf8
Compress-Archive -Path (Join-Path $output '*') -DestinationPath $zip -CompressionLevel Optimal
Get-FileHash $zip -Algorithm SHA256
