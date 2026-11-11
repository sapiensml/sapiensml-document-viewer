<#
.SYNOPSIS
Installs, upgrades and manages the SapiensML Document Viewer as a Windows service (Windows Server 2016 or newer).

.DESCRIPTION
Run from an elevated PowerShell in the unpacked Windows release (made by deploy/package.sh). Whatever the viewer
needs and the server lacks is downloaded and checked against a pinned checksum: the Eclipse Temurin Java 21
runtime, WinSW (runs the viewer as a service, under its own virtual account), and for "All" also Keycloak and
PostgreSQL. The viewer serves HTTPS itself; no IIS or nginx is needed.

  .\install.ps1 All -ViewerHost viewer.example.com -KeycloakHost sso.example.com -SelfSigned
        Everything on one fresh server: Java, PostgreSQL, Keycloak (port 8443), the viewer (port 443), the first
        admin user. For a real certificate use -PfxFile cert.pfx -PfxPassword (Read-Host -AsSecureString)
  .\install.ps1 Install [-ViewerHost NAME (-PfxFile FILE -PfxPassword PW | -SelfSigned)]
        Install, or upgrade to the jar next to this script. Keeps the settings; after an upgrade waits for the
        viewer to be healthy and goes back to the previous release if not
  .\install.ps1 Deps        only download what is missing (Java 21, WinSW)
  .\install.ps1 Rollback    back to the previous release
  .\install.ps1 Status      service state, health, installed releases
  .\install.ps1 Uninstall [-Purge]   remove the service and releases (-Purge: settings, logs too)

Layout: C:\SapiensML\viewer (service wrapper and releases), C:\ProgramData\SapiensML\viewer\viewer.properties (settings and
secrets, administrators and the service only), ...\viewer\data (the Alfresco connection saved on the admin page),
...\viewer\logs (service output and the audit trail).

.EXAMPLE
.\install.ps1 All -ViewerHost viewer.example.com -KeycloakHost sso.example.com -PfxFile C:\certs\sapiensml.pfx -PfxPassword (Read-Host -AsSecureString)
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('All', 'Install', 'Deps', 'Rollback', 'Status', 'Uninstall')]
    [string] $Command,
    # Public host names (two names, both pointing at this server)
    [string] $ViewerHost,
    [string] $KeycloakHost,
    # TLS: a .pfx with the certificate (and its chain) for both names, or -SelfSigned on a test server
    [string] $PfxFile,
    [SecureString] $PfxPassword,
    [string] $KeycloakPfxFile,
    [SecureString] $KeycloakPfxPassword,
    [switch] $SelfSigned,
    # The internal CA that signed the certificate, so the viewer trusts the Keycloak site
    [string] $CaCert,
    [int] $ViewerPort = 443,
    [int] $KeycloakPort = 8443,
    # Keycloak's database: PostgreSQL on this server (installed if missing), its file database (test servers),
    # or an existing PostgreSQL
    [ValidateSet('Local', 'DevFile', 'External')]
    [string] $KeycloakDb = 'Local',
    [string] $DbUrl,
    [string] $DbUsername,
    [string] $DbPasswordFile,
    [string] $PostgresSuperuserPasswordFile,
    [string] $FirstUser = 'vieweradmin',
    # Test servers without DNS records: resolve both names to this server in the hosts file
    [switch] $AddHosts,
    [string] $InstallRoot = "$env:SystemDrive\SapiensML",
    [string] $DataRoot = "$env:ProgramData\SapiensML",
    # Offline servers: files copied over beforehand instead of downloads
    [string] $JavaArchive,
    [string] $KeycloakArchive,
    [string] $WinswExe,
    [string] $PostgresInstaller,
    [switch] $NoDeps,
    [switch] $Purge
)

. (Join-Path $PSScriptRoot 'lib.ps1')

$ServiceId = 'sapiensml-document-viewer'
$ViewerHome = Join-Path $InstallRoot 'viewer'
$Releases = Join-Path $ViewerHome 'releases'
$ServiceXml = Join-Path $ViewerHome "$ServiceId.xml"
$ViewerData = Join-Path $DataRoot 'viewer'
$Properties = Join-Path $ViewerData 'viewer.properties'
$SettingsDir = Join-Path $ViewerData 'data'
$LogDir = Join-Path $ViewerData 'logs'
$TrustStore = Join-Path $ViewerData 'truststore.p12'
$TlsDir = Join-Path $DataRoot 'tls'
$KeepReleases = 5

function Get-PublicUrl([string] $HostName, [int] $Port) {
    if ($Port -eq 443) { return "https://$HostName" }
    return "https://$($HostName):$Port"
}

function Find-ViewerJar {
    foreach ($candidate in @((Join-Path $PSScriptRoot "$ServiceId.jar"), (Join-Path $PSScriptRoot "..\..\target\$ServiceId.jar"))) {
        if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw "No $ServiceId.jar next to this script (build the release with deploy/package.sh)."
}

function Get-ReleaseVersion {
    $file = Join-Path $PSScriptRoot 'VERSION'
    if (Test-Path -LiteralPath $file) { return (Get-Content -LiteralPath $file -Raw).Trim() }
    return 'unknown'
}

function Get-RecordedRelease([string] $Name) {
    $file = Join-Path $ViewerHome $Name
    if (Test-Path -LiteralPath $file) { return (Get-Content -LiteralPath $file -Raw).Trim() }
    return ''
}

function Write-ViewerServiceXml([string] $Java, [string] $Jar) {
    $javaOptions = Get-PropertiesValue $Properties 'JAVA_OPTS' '-Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError'
    $import = 'optional:file:' + (ConvertTo-ForwardSlash $Properties)
    $arguments = "$javaOptions -jar `"$Jar`" --spring.profiles.active=prod `"--spring.config.import=$import`""
    $xml = New-ServiceXml -Id $ServiceId -Name 'SapiensML Document Viewer' `
        -Description 'SapiensML Document Viewer: opens Alfresco documents from links in business applications' -Executable $Java -Arguments $arguments `
        -WorkingDirectory $ViewerHome -LogDirectory $LogDir
    Write-TextFile $ServiceXml $xml
    Write-TextFile (Join-Path $ViewerHome 'current-release.txt') $Jar
}

# Folders, settings file, jar, service (registered, not started). Returns @{ Java; Account; Release; Previous }
function Install-ViewerFiles([string] $Java) {
    if ($InstallRoot -match '\s') { throw "-InstallRoot must not contain spaces ($InstallRoot)." }
    # Its paths go into the service's Java options, which are split at spaces
    if ($DataRoot -match '\s') { throw "-DataRoot must not contain spaces ($DataRoot)." }
    $firstRun = -not (Test-Path -LiteralPath $ViewerData)
    foreach ($dir in @($ViewerHome, $Releases, $ViewerData, $SettingsDir, $LogDir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    # ProgramData lets every user read by default: administrators only before any secret is written
    if ($firstRun) { Protect-Path -Path $ViewerData -Readers @() }
    $wrapper = Install-ServiceWrapper -InstallRoot $InstallRoot -Directory $ViewerHome -Id $ServiceId -WinswExe $WinswExe
    if (-not (Test-Path -LiteralPath $Properties)) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'viewer.properties.example') -Destination $Properties
        Set-PropertiesValue $Properties 'VIEWER_SETTINGS_DIR' (ConvertTo-ForwardSlash $SettingsDir)
        Set-PropertiesValue $Properties 'VIEWER_AUDIT_DIR' (ConvertTo-ForwardSlash $LogDir)
        Write-Note "Created $Properties (administrators and the service only; it holds the client secrets)"
    }
    if (-not (Get-PropertiesValue $Properties 'VIEWER_SETTINGS_KEY')) {
        $key = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($key) } finally { $rng.Dispose() }
        Set-PropertiesValue $Properties 'VIEWER_SETTINGS_KEY' ([Convert]::ToBase64String($key))
        Write-Note "Generated VIEWER_SETTINGS_KEY in $Properties (keep a copy in the password manager)"
    }

    $version = Get-ReleaseVersion
    $release = Join-Path $Releases ("$ServiceId-$version-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.jar')
    Copy-Item -LiteralPath (Find-ViewerJar) -Destination $release
    $previous = Get-RecordedRelease 'current-release.txt'
    if ($previous -and ($previous -ne $release)) { Write-TextFile (Join-Path $ViewerHome 'previous-release.txt') $previous }
    Write-ViewerServiceXml -Java $Java -Jar $release

    $account = Register-WrappedService -Wrapper $wrapper -Id $ServiceId
    Protect-Path -Path $ViewerHome -Readers @($account)
    Protect-Path -Path $ViewerData -Readers @($account)
    Protect-Path -Path $SettingsDir -Writers @($account)
    Protect-Path -Path $LogDir -Writers @($account)
    Protect-Path -Path $Properties -Readers @($account)
    Write-Note "Installed $ServiceId $version as $release (Java: $Java, account: $account)"
    return @{ Java = $Java; Account = $account; Release = $release; Previous = $previous }
}

# The viewer serves HTTPS itself with this certificate
function Set-ViewerTls([string] $Pfx, [string] $Password, [string] $Account) {
    Assert-PortFree -Port $ViewerPort -ServiceId $ServiceId
    New-Item -ItemType Directory -Force -Path $TlsDir | Out-Null
    $target = Join-Path $TlsDir 'viewer.pfx'
    if ((Resolve-Path -LiteralPath $Pfx).Path -ne $target) { Copy-Item -LiteralPath $Pfx -Destination $target -Force }
    Protect-Path -Path $target -Readers @($Account)
    Set-PropertiesValue $Properties 'server.ssl.enabled' 'true'
    Set-PropertiesValue $Properties 'server.ssl.key-store' ('file:' + (ConvertTo-ForwardSlash $target))
    Set-PropertiesValue $Properties 'server.ssl.key-store-type' 'PKCS12'
    Set-PropertiesValue $Properties 'server.ssl.key-store-password' $Password
    Set-PropertiesValue $Properties 'VIEWER_ADDRESS' '0.0.0.0'
    Set-PropertiesValue $Properties 'VIEWER_PORT' ([string] $ViewerPort)
    Open-FirewallPort -Name 'SapiensML Document Viewer (HTTPS)' -Port $ViewerPort
}

# Makes the viewer trust one more certificate (self-signed Keycloak site, internal CA), keeping the public CAs
function Set-ViewerTrust([string] $Java, [string] $Certificate, [string] $Account) {
    New-JavaTrustStore -Java $Java -Certificate $Certificate -Target $TrustStore
    Protect-Path -Path $TrustStore -Readers @($Account)
    $options = Get-PropertiesValue $Properties 'JAVA_OPTS'
    if ($options -notmatch 'javax\.net\.ssl\.trustStore=') {
        $store = ConvertTo-ForwardSlash $TrustStore
        Set-PropertiesValue $Properties 'JAVA_OPTS' "$options -Djavax.net.ssl.trustStore=$store -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"
    }
    Write-Note "The viewer trusts $Certificate"
}

# Stop, then start: a failing start is reported by the health check below, with the log lines
function Restart-Viewer {
    Stop-Service -Name $ServiceId -Force -ErrorAction SilentlyContinue
    Start-Service -Name $ServiceId -ErrorAction SilentlyContinue
}

function Start-ViewerVerified([hashtable] $Installed) {
    Restart-Viewer
    $health = "http://127.0.0.1:$(Get-PropertiesValue $Properties 'VIEWER_MANAGEMENT_PORT' '8081')/actuator/health/readiness"
    Write-Note "Waiting for $health ..."
    if (Wait-HttpOk -Url $health -TimeoutSeconds 180) {
        Write-Note 'The viewer is up'
        Remove-OldReleases
        return
    }
    Write-Note 'The viewer did not become healthy. Last log lines:'
    Get-ChildItem -Path (Join-Path $LogDir "$ServiceId*.log") -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 2 | ForEach-Object { Get-Content -LiteralPath $_.FullName -Tail 20 }
    if ($Installed.Previous -and (Test-Path -LiteralPath $Installed.Previous)) {
        Write-Note "Going back to $($Installed.Previous)"
        Write-ViewerServiceXml -Java $Installed.Java -Jar $Installed.Previous
        Restart-Viewer
        Wait-HttpOk -Url $health -TimeoutSeconds 180 | Out-Null
    }
    throw "The viewer did not start; see $LogDir"
}

function Remove-OldReleases {
    $keep = @((Get-RecordedRelease 'current-release.txt'), (Get-RecordedRelease 'previous-release.txt'))
    Get-ChildItem -Path (Join-Path $Releases '*.jar') | Sort-Object LastWriteTime -Descending | Select-Object -Skip $KeepReleases |
        Where-Object { $keep -notcontains $_.FullName } | Remove-Item -Force
}

function Test-NeedsSettings {
    foreach ($line in [IO.File]::ReadAllLines($Properties)) {
        if (-not $line.StartsWith('#') -and $line -match 'CHANGE_ME') { return $true }
    }
    return $false
}

# ---- Commands ------------------------------------------------------------------------------------------------------

function Invoke-All {
    if (-not $ViewerHost -or -not $KeycloakHost) { throw 'All needs -ViewerHost and -KeycloakHost.' }
    if ($ViewerHost -eq $KeycloakHost -and $ViewerPort -eq $KeycloakPort) { throw 'The viewer and Keycloak need different host names or ports.' }
    if (-not $SelfSigned -and (-not $PfxFile -or -not $PfxPassword)) { throw 'All needs -PfxFile and -PfxPassword, or -SelfSigned.' }
    # Before anything is installed
    Assert-PortFree -Port $ViewerPort -ServiceId $ServiceId

    Write-Step 'Dependencies'
    $java = Install-Java21 -InstallRoot $InstallRoot -JavaArchive $JavaArchive -NoDeps:$NoDeps

    # The viewer calls Keycloak by its public name, so this server must resolve it
    foreach ($name in @($KeycloakHost, $ViewerHost)) {
        if (-not (Test-Resolves $name)) {
            if ($AddHosts) { Add-HostsEntry $name }
            elseif ($name -eq $KeycloakHost) { throw "This server cannot resolve $name. Create DNS records for both names, or add -AddHosts (test servers)." }
        }
    }

    $viewerPfx = $PfxFile
    $viewerPassword = ConvertFrom-SecureStringPlain $PfxPassword
    $trust = $CaCert
    if ($SelfSigned) {
        $generated = New-SelfSignedPfx -DnsNames @($ViewerHost, $KeycloakHost) -Directory $TlsDir
        $viewerPfx = $generated.Pfx
        $viewerPassword = $generated.Password
        $trust = $generated.Cer
    }
    $keycloakPfx = $viewerPfx
    $keycloakPassword = ConvertTo-SecureString -String $viewerPassword -AsPlainText -Force
    if ($KeycloakPfxFile) {
        $keycloakPfx = $KeycloakPfxFile
        $keycloakPassword = $KeycloakPfxPassword
    }

    Write-Step 'Viewer files'
    $installed = Install-ViewerFiles -Java $java

    Write-Step 'Keycloak'
    $viewerUrl = Get-PublicUrl $ViewerHost $ViewerPort
    $keycloakArgs = @{
        KeycloakHost = $KeycloakHost; Port = $KeycloakPort; ViewerUrl = $viewerUrl
        PfxFile = $keycloakPfx; PfxPassword = $keycloakPassword
        Db = $KeycloakDb; ViewerProperties = $Properties; FirstUser = $FirstUser
        InstallRoot = $InstallRoot; DataRoot = $DataRoot
    }
    foreach ($optional in @('DbUrl', 'DbUsername', 'DbPasswordFile', 'PostgresSuperuserPasswordFile', 'JavaArchive', 'WinswExe', 'PostgresInstaller')) {
        $value = Get-Variable -Name $optional -ValueOnly
        if ($value) { $keycloakArgs[$optional] = $value }
    }
    if ($KeycloakArchive) { $keycloakArgs['Archive'] = $KeycloakArchive }
    if ($trust) { $keycloakArgs['TrustCertificate'] = $trust }
    if ($NoDeps) { $keycloakArgs['NoDeps'] = $true }
    & (Join-Path $PSScriptRoot 'install-keycloak.ps1') @keycloakArgs

    Write-Step 'HTTPS for the viewer'
    Set-ViewerTls -Pfx $viewerPfx -Password $viewerPassword -Account $installed.Account
    if ($trust) { Set-ViewerTrust -Java $java -Certificate $trust -Account $installed.Account }
    # JAVA_OPTS may have changed (trust store): rewrite the service definition before the first start
    Write-ViewerServiceXml -Java $java -Jar $installed.Release

    Write-Step 'Starting the viewer'
    Start-ViewerVerified $installed

    Write-Host ''
    Write-Host 'Done. Next:' -ForegroundColor Green
    Write-Note "  1. Sign in at $viewerUrl/admin as $FirstUser (password printed above; change it on first sign-in)"
    Write-Note '     and enter the Alfresco connection (README.md, "Enter the Alfresco connection").'
    Write-Note "  2. Create the other users in Keycloak ($(Get-PublicUrl $KeycloakHost $KeycloakPort)/admin, realm sapiensml) with the role"
    Write-Note '     DOCUMENT_VIEWER, or connect Okta there under Identity providers.'
    if ($SelfSigned) { Write-Note '  The certificate is self-signed: browsers warn until it is replaced (Install -PfxFile ...).' }
}

function Invoke-Install {
    $java = Install-Java21 -InstallRoot $InstallRoot -JavaArchive $JavaArchive -NoDeps:$NoDeps
    $installed = Install-ViewerFiles -Java $java
    if ($SelfSigned) {
        if (-not $ViewerHost) { throw '-SelfSigned needs -ViewerHost.' }
        $generated = New-SelfSignedPfx -DnsNames @($ViewerHost) -Directory $TlsDir
        Set-ViewerTls -Pfx $generated.Pfx -Password $generated.Password -Account $installed.Account
    }
    elseif ($PfxFile) {
        if (-not $PfxPassword) { throw '-PfxFile needs -PfxPassword.' }
        Set-ViewerTls -Pfx $PfxFile -Password (ConvertFrom-SecureStringPlain $PfxPassword) -Account $installed.Account
    }
    if ($CaCert) { Set-ViewerTrust -Java $java -Certificate $CaCert -Account $installed.Account }
    Write-ViewerServiceXml -Java $java -Jar $installed.Release
    if (Test-NeedsSettings) {
        Write-Host ''
        Write-Note "Next: fill in every CHANGE_ME in $Properties (sign-in settings), then run: Start-Service $ServiceId"
        Write-Note 'Then sign in at https://<viewer host>/admin as a VIEWER_ADMIN user and enter the Alfresco connection.'
        if ((Get-PropertiesValue $Properties 'server.ssl.enabled') -ne 'true') {
            Write-Note 'HTTPS is not set up yet: run Install again with -ViewerHost and -PfxFile/-PfxPassword (or -SelfSigned).'
        }
        return
    }
    Start-ViewerVerified $installed
}

function Invoke-Deps {
    Install-Java21 -InstallRoot $InstallRoot -JavaArchive $JavaArchive | Out-Null
    $download = Join-Path (Get-DownloadDirectory $InstallRoot) 'WinSW-x64.exe'
    Get-VerifiedFile -Url $script:WinSW.Url -Target $download -Sha256 $script:WinSW.Sha256 -LocalCopy $WinswExe
    Write-Note 'Everything the viewer needs is downloaded'
}

function Invoke-Rollback {
    $previous = Get-RecordedRelease 'previous-release.txt'
    if (-not $previous -or -not (Test-Path -LiteralPath $previous)) { throw 'No previous release recorded.' }
    $current = Get-RecordedRelease 'current-release.txt'
    $java = Install-Java21 -InstallRoot $InstallRoot -NoDeps
    Write-ViewerServiceXml -Java $java -Jar $previous
    Write-TextFile (Join-Path $ViewerHome 'previous-release.txt') $current
    Start-ViewerVerified @{ Java = $java; Previous = $null; Release = $previous }
    Write-Note "Now running $previous"
}

function Invoke-Status {
    Get-Service -Name $ServiceId -ErrorAction SilentlyContinue | Format-Table -AutoSize Name, Status, StartType
    Write-Note "Current release: $(Get-RecordedRelease 'current-release.txt')"
    Write-Note "Previous release: $(Get-RecordedRelease 'previous-release.txt')"
    try {
        $port = Get-PropertiesValue $Properties 'VIEWER_MANAGEMENT_PORT' '8081'
        Write-Note "Health: $((Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/health" -UseBasicParsing -TimeoutSec 5).Content)"
    }
    catch {
        Write-Note 'Health: not answering'
    }
}

function Invoke-Uninstall {
    if (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue) {
        Stop-Service -Name $ServiceId -Force -ErrorAction SilentlyContinue
        Invoke-Native -FilePath (Join-Path $ViewerHome "$ServiceId.exe") -Arguments @('uninstall') | Out-Null
    }
    if (Test-Path -LiteralPath $ViewerHome) { Remove-Item -LiteralPath $ViewerHome -Recurse -Force }
    if ($Purge) {
        if (Test-Path -LiteralPath $ViewerData) { Remove-Item -LiteralPath $ViewerData -Recurse -Force }
        Get-NetFirewallRule -DisplayName 'SapiensML Document Viewer (HTTPS)' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        Write-Note "Removed $ServiceId, its settings and logs (Keycloak, PostgreSQL and Java stay installed)"
    }
    else {
        Write-Note "Removed $ServiceId; kept $ViewerData (use -Purge to remove it)"
    }
}

Assert-Administrator
switch ($Command) {
    'All' { Invoke-All }
    'Install' { Invoke-Install }
    'Deps' { Invoke-Deps }
    'Rollback' { Invoke-Rollback }
    'Status' { Invoke-Status }
    'Uninstall' { Invoke-Uninstall }
}
