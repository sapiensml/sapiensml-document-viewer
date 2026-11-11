<#
.SYNOPSIS
Installs Keycloak as a Windows service with the sapiensml realm and the viewer's client.

.DESCRIPTION
For a server that has no organisation Keycloak to use. Run from an elevated PowerShell. Java 21, WinSW and (with
-Db Local) PostgreSQL 17 are downloaded when missing and checked against pinned checksums. Keycloak serves HTTPS
itself (default port 8443) with the given .pfx; its health checks answer on http://127.0.0.1:9000 only.
install.ps1 All runs this script for you.

.EXAMPLE
.\install-keycloak.ps1 -KeycloakHost sso.example.com -ViewerUrl https://viewer.example.com -Db Local `
    -PfxFile C:\certs\sso.pfx -PfxPassword (Read-Host -AsSecureString) `
    -ViewerProperties C:\ProgramData\SapiensML\viewer\viewer.properties -FirstUser vieweradmin
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $KeycloakHost,
    [int] $Port = 8443,
    # The viewer's public address, for its redirect URIs
    [Parameter(Mandatory = $true)] [string] $ViewerUrl,
    [Parameter(Mandatory = $true)] [string] $PfxFile,
    [Parameter(Mandatory = $true)] [SecureString] $PfxPassword,
    # The certificate (or its CA) when it is self-signed or internal, so the admin tool trusts the site
    [string] $TrustCertificate,
    # PostgreSQL on this server (installed if missing), Keycloak's file database (test servers), or an existing PostgreSQL
    [ValidateSet('Local', 'DevFile', 'External')] [string] $Db = 'Local',
    [string] $DbUrl,
    [string] $DbUsername,
    [string] $DbPasswordFile,
    [string] $PostgresSuperuserPasswordFile,
    # Write the issuer and the viewer's client secret into the viewer's viewer.properties
    [string] $ViewerProperties,
    # Create this user in realm sapiensml with both viewer roles and a one-time password
    [string] $FirstUser,
    [switch] $AddHosts,
    [string] $InstallRoot = "$env:SystemDrive\SapiensML",
    [string] $DataRoot = "$env:ProgramData\SapiensML",
    [string] $Archive,
    [string] $JavaArchive,
    [string] $WinswExe,
    [string] $PostgresInstaller,
    [switch] $NoDeps
)

. (Join-Path $PSScriptRoot 'lib.ps1')

$ServiceId = 'sapiensml-keycloak'
$Version = $script:KeycloakRelease.Version
$KcRoot = Join-Path $InstallRoot 'keycloak'
$KcHome = Join-Path $KcRoot "keycloak-$Version"
$KcData = Join-Path $DataRoot 'keycloak'
$KcLogs = Join-Path $KcData 'logs'
$Secrets = Join-Path $KcData 'keycloak.secrets'
$TlsDir = Join-Path $DataRoot 'tls'
$ManagementPort = 9000

Assert-Administrator
if ($InstallRoot -match '\s') { throw "-InstallRoot must not contain spaces ($InstallRoot): Keycloak's kc.bat needs a plain path." }
if ($FirstUser -and $FirstUser -notmatch '^[A-Za-z0-9._@-]{1,64}$') { throw '-FirstUser: letters, digits, . _ @ - only.' }
if ($Db -eq 'External' -and (-not $DbUrl -or -not $DbUsername -or -not $DbPasswordFile)) {
    throw '-Db External needs -DbUrl, -DbUsername and -DbPasswordFile.'
}
$PublicUrl = "https://$($KeycloakHost):$Port"
if ($Port -eq 443) { $PublicUrl = "https://$KeycloakHost" }
$ViewerUrl = $ViewerUrl.TrimEnd('/')
$realmTemplate = @((Join-Path $PSScriptRoot 'sapiensml-realm.template.json'), (Join-Path $PSScriptRoot '..\keycloak\sapiensml-realm.template.json')) |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $realmTemplate) { throw 'sapiensml-realm.template.json not found next to this script.' }
if (-not (Test-Resolves $KeycloakHost)) {
    if ($AddHosts) { Add-HostsEntry $KeycloakHost }
    else { throw "This server cannot resolve $KeycloakHost. Create a DNS record, or add -AddHosts (test servers)." }
}

# ---- Dependencies ------------------------------------------------------------------------------------------------

Write-Step 'Keycloak: dependencies'
$java = Install-Java21 -InstallRoot $InstallRoot -JavaArchive $JavaArchive -NoDeps:$NoDeps
$javaHome = Get-JavaHome $java
$superuserPassword = $null
if ($Db -eq 'Local') {
    $superuserPassword = Install-Postgres -InstallRoot $InstallRoot -DataRoot $DataRoot -Installer $PostgresInstaller `
        -SuperuserPasswordFile $PostgresSuperuserPasswordFile -NoDeps:$NoDeps
}

# ---- Keycloak itself ---------------------------------------------------------------------------------------------

Assert-PortFree -Port $Port -ServiceId $ServiceId
Assert-PortFree -Port $ManagementPort -ServiceId $ServiceId

Write-Step "Keycloak $Version"
$firstRun = -not (Test-Path -LiteralPath $KcData)
foreach ($dir in @($KcRoot, $KcData, $KcLogs, $TlsDir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
# Administrators only before secrets are written (keycloak.conf, keycloak.secrets, the certificate)
if ($firstRun) {
    Protect-Path -Path $KcRoot -Readers @()
    Protect-Path -Path $KcData -Readers @()
}
if (-not (Get-ChildItem -LiteralPath $TlsDir -Force | Select-Object -First 1)) { Protect-Path -Path $TlsDir -Readers @() }
if (-not (Test-Path -LiteralPath (Join-Path $KcHome 'bin\kc.bat'))) {
    $zip = Join-Path (Get-DownloadDirectory $InstallRoot) "keycloak-$Version.zip"
    Get-VerifiedFile -Url $script:KeycloakRelease.Url -Target $zip -Sha1 $script:KeycloakRelease.Sha1 -LocalCopy $Archive
    Expand-Archive -LiteralPath $zip -DestinationPath $KcRoot -Force
    if (-not (Test-Path -LiteralPath (Join-Path $KcHome 'bin\kc.bat'))) { throw "The archive did not contain keycloak-$Version." }
}

$firstInstall = -not (Test-Path -LiteralPath $Secrets)
if ($firstInstall) {
    $dbPassword = ''
    if ($Db -eq 'Local') { $dbPassword = New-RandomSecret 24 }
    if ($Db -eq 'External') { $dbPassword = (Get-Content -LiteralPath $DbPasswordFile -Raw).Trim() }
    Write-TextFile $Secrets ("BOOTSTRAP_ADMIN_USERNAME=admin`r`nBOOTSTRAP_ADMIN_PASSWORD=$(New-RandomSecret 15)`r`n" +
        "DB_PASSWORD=$dbPassword`r`n")
    Protect-Path -Path $Secrets -Readers @()
}
$adminUser = Get-PropertiesValue $Secrets 'BOOTSTRAP_ADMIN_USERNAME' 'admin'
$adminPassword = Get-PropertiesValue $Secrets 'BOOTSTRAP_ADMIN_PASSWORD'
$dbPassword = Get-PropertiesValue $Secrets 'DB_PASSWORD'
if ($Db -eq 'Local') { New-PostgresDatabase -SuperuserPassword $superuserPassword -Name 'keycloak' -User 'keycloak' -Password $dbPassword }

$keycloakPfx = Join-Path $TlsDir 'keycloak.pfx'
if ((Resolve-Path -LiteralPath $PfxFile).Path -ne $keycloakPfx) { Copy-Item -LiteralPath $PfxFile -Destination $keycloakPfx -Force }

$dbName = 'postgres'
if ($Db -eq 'DevFile') { $dbName = 'dev-file' }
$conf = New-Object Collections.Generic.List[string]
$conf.Add('# Written by install-keycloak.ps1 (administrators and the Keycloak service only). See https://www.keycloak.org/server/all-config')
$conf.Add("hostname=$PublicUrl")
$conf.Add("https-port=$Port")
$conf.Add('http-enabled=false')
$conf.Add("https-key-store-file=$(ConvertTo-ForwardSlash $keycloakPfx)")
$conf.Add('https-key-store-type=PKCS12')
$conf.Add("https-key-store-password=$(ConvertFrom-SecureStringPlain $PfxPassword)")
# Health on plain HTTP, loopback only: the installer and monitoring check it without the certificate
$conf.Add('http-management-scheme=http')
$conf.Add('http-management-host=127.0.0.1')
$conf.Add("http-management-port=$ManagementPort")
$conf.Add("db=$dbName")
if ($Db -eq 'Local') {
    $conf.Add('db-url=jdbc:postgresql://127.0.0.1:5432/keycloak')
    $conf.Add('db-username=keycloak')
    $conf.Add("db-password=$dbPassword")
}
elseif ($Db -eq 'External') {
    $conf.Add("db-url=$DbUrl")
    $conf.Add("db-username=$DbUsername")
    $conf.Add("db-password=$dbPassword")
}
$confFile = Join-Path $KcHome 'conf\keycloak.conf'
Write-TextFile $confFile (($conf -join "`r`n") + "`r`n")

Write-Note "Building Keycloak for database $dbName ..."
$env:JAVA_HOME = $javaHome
# "--option value" rather than "--option=value": cmd.exe splits batch arguments at "="
Invoke-Native -FilePath (Join-Path $KcHome 'bin\kc.bat') -Arguments @('build', '--db', $dbName, '--health-enabled', 'true') | Out-Null

$clientSecret = ''
$importFile = Join-Path $KcHome 'data\import\sapiensml-realm.json'
if ($firstInstall) {
    $clientSecret = New-RandomSecret 24
    New-Item -ItemType Directory -Force -Path (Split-Path $importFile) | Out-Null
    $realm = [IO.File]::ReadAllText($realmTemplate)
    $realm = $realm.Replace('@VIEWER_CLIENT_SECRET@', $clientSecret)
    $realm = $realm.Replace('@VIEWER_REDIRECT_URIS@', "`"$ViewerUrl/login/oauth2/code/viewer`"")
    $realm = $realm.Replace('@VIEWER_POST_LOGOUT_URIS@', "$ViewerUrl/signed-out")
    Write-TextFile $importFile $realm
}

$wrapper = Install-ServiceWrapper -InstallRoot $InstallRoot -Directory $KcRoot -Id $ServiceId -WinswExe $WinswExe
$depends = @()
if ($Db -eq 'Local') {
    $depends = @(Get-Service -Name 'postgresql-x64-*' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Name)
}
# kc.bat picks $JAVA_HOME (or whatever java is on the PATH): always the Java 21
$environment = @{
    JAVA_HOME = $javaHome
    JAVA_OPTS_KC_HEAP = '-Xms256m -Xmx1g'
    KC_BOOTSTRAP_ADMIN_USERNAME = $adminUser
    KC_BOOTSTRAP_ADMIN_PASSWORD = $adminPassword
}
$xml = New-ServiceXml -Id $ServiceId -Name 'SapiensML Keycloak' -Description 'Sign-in for the SapiensML Document Viewer' `
    -Executable (Join-Path $env:SystemRoot 'System32\cmd.exe') `
    -Arguments "/c $(Join-Path $KcHome 'bin\kc.bat') start --optimized --import-realm" `
    -WorkingDirectory $KcHome -LogDirectory $KcLogs -Environment $environment -DependsOn $depends
Write-TextFile (Join-Path $KcRoot "$ServiceId.xml") $xml

$account = Register-WrappedService -Wrapper $wrapper -Id $ServiceId
Protect-Path -Path $KcRoot -Readers @($account)
Protect-Path -Path (Join-Path $KcHome 'data') -Writers @($account)
Protect-Path -Path $KcLogs -Writers @($account)
Protect-Path -Path $keycloakPfx -Readers @($account)
Open-FirewallPort -Name 'SapiensML Keycloak (HTTPS)' -Port $Port

Stop-Service -Name $ServiceId -Force -ErrorAction SilentlyContinue
Start-Service -Name $ServiceId -ErrorAction SilentlyContinue
Write-Note 'Waiting for Keycloak to be ready ...'
if (-not (Wait-HttpOk -Url "http://127.0.0.1:$ManagementPort/health/ready" -TimeoutSeconds 300)) {
    Get-ChildItem -Path (Join-Path $KcLogs "$ServiceId*.log") -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending |
        Select-Object -First 2 | ForEach-Object { Get-Content -LiteralPath $_.FullName -Tail 30 }
    throw "Keycloak did not become ready; see $KcLogs"
}
# The realm is in the database now; the file with the client secret is not needed any more
if (Test-Path -LiteralPath $importFile) { Remove-Item -LiteralPath $importFile -Force }

# ---- Realm users and the viewer's settings -----------------------------------------------------------------------

$kcadm = Join-Path $KcHome 'bin\kcadm.bat'
$kcadmConfig = Join-Path $KcData 'kcadm.config'
function Invoke-Kcadm([string[]] $Arguments) {
    $lines = Invoke-Native -FilePath $kcadm -Arguments ($Arguments + @('--config', $kcadmConfig))
    return , @($lines | Where-Object { $_ -and $_ -notmatch '^Logging into' })
}
try {
    if ($TrustCertificate) {
        $kcadmTrust = Join-Path $KcData 'kcadm-truststore.p12'
        New-JavaTrustStore -Java $java -Certificate $TrustCertificate -Target $kcadmTrust
        Invoke-Kcadm @('config', 'truststore', '--trustpass', 'changeit', $kcadmTrust) | Out-Null
    }
    Invoke-Kcadm @('config', 'credentials', '--server', $PublicUrl, '--realm', 'master', '--user', $adminUser, '--password', $adminPassword) | Out-Null

    if (-not $clientSecret -and $ViewerProperties) {
        # Run again after an interrupted install: read the secret back instead of inventing a new one
        $client = Invoke-Kcadm @('get', 'clients', '-r', 'sapiensml', '-q', 'clientId=sapiensml-document-viewer', '--fields', 'id', '--format', 'csv', '--noquotes') |
            Select-Object -First 1
        $clientSecret = Invoke-Kcadm @('get', "clients/$client/client-secret", '-r', 'sapiensml', '--fields', 'value', '--format', 'csv', '--noquotes') |
            Select-Object -First 1
    }

    $firstPassword = ''
    if ($FirstUser) {
        $existing = Invoke-Kcadm @('get', 'users', '-r', 'sapiensml', '-q', "username=$FirstUser", '-q', 'exact=true', '--fields', 'id', '--format', 'csv', '--noquotes')
        if ($existing.Count -eq 0) {
            $firstPassword = New-RandomSecret 10
            Invoke-Kcadm @('create', 'users', '-r', 'sapiensml', '-s', "username=$FirstUser", '-s', 'enabled=true') | Out-Null
            Invoke-Kcadm @('set-password', '-r', 'sapiensml', '--username', $FirstUser, '--new-password', $firstPassword, '--temporary') | Out-Null
            Invoke-Kcadm @('add-roles', '-r', 'sapiensml', '--uusername', $FirstUser, '--rolename', 'VIEWER_ADMIN', '--rolename', 'DOCUMENT_VIEWER') | Out-Null
        }
    }
}
finally {
    if (Test-Path -LiteralPath $kcadmConfig) { Remove-Item -LiteralPath $kcadmConfig -Force }
}

if ($ViewerProperties) {
    Set-PropertiesValue $ViewerProperties 'VIEWER_OIDC_ISSUER_URI' "$PublicUrl/realms/sapiensml"
    Set-PropertiesValue $ViewerProperties 'VIEWER_OIDC_CLIENT_ID' 'sapiensml-document-viewer'
    if ($clientSecret) { Set-PropertiesValue $ViewerProperties 'VIEWER_OIDC_CLIENT_SECRET' $clientSecret }
}

Write-Host ''
Write-Host "Keycloak $Version is running: $PublicUrl (issuer $PublicUrl/realms/sapiensml, database $dbName)" -ForegroundColor Green
if ($firstInstall) { Write-Note "Temporary Keycloak admin: $adminUser / $adminPassword (admin console $PublicUrl/admin; also in $Secrets)" }
if ($ViewerProperties) { Write-Note "Wrote the issuer and the viewer's client secret into $ViewerProperties" }
elseif ($clientSecret) {
    Write-Note "Viewer client secret (VIEWER_OIDC_CLIENT_SECRET): $clientSecret"
    Write-Note "Issuer (VIEWER_OIDC_ISSUER_URI): $PublicUrl/realms/sapiensml"
}
if ($firstPassword) { Write-Note "First user: $FirstUser / $firstPassword (one-time password, changed at the first sign-in; roles VIEWER_ADMIN and DOCUMENT_VIEWER)" }
Write-Note 'Give users the realm role DOCUMENT_VIEWER, and whoever sets up the Alfresco connection VIEWER_ADMIN.'
