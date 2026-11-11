# Shared by install.ps1 and install-keycloak.ps1 (dot-sourced). Windows PowerShell 5.1 or PowerShell 7.
# Downloads what is missing and checks every download against a pinned checksum: the Eclipse Temurin Java 21
# runtime, WinSW (the service wrapper), Keycloak and the PostgreSQL installer. Nothing already installed is
# upgraded or removed.

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 shows a progress bar that makes downloads very slow, and may default to TLS 1.0
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$script:Temurin = @{
    Release = '21.0.13+9'
    Url     = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip'
    Sha256  = '2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628'
}
$script:WinSW = @{
    Url    = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
    Sha256 = '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da'
}
$script:KeycloakRelease = @{
    Version = '26.8.2'
    Url     = 'https://github.com/keycloak/keycloak/releases/download/26.8.2/keycloak-26.8.2.zip'
    Sha1    = '987faddc433a9bf72f219e3654bd02b7685621bc'
}
$script:PostgresRelease = @{
    Major  = '17'
    Url    = 'https://get.enterprisedb.com/postgresql/postgresql-17.11-5-windows-x64.exe'
    Sha256 = '612c7f5400a003aaffa0372cb273eed52278f93c680ab911eb9cb2229e329072'
}

# Well-known SIDs: group names are translated on non-English Windows
$script:Administrators = '*S-1-5-32-544'
$script:LocalSystem = '*S-1-5-18'

function Write-Step([string] $Message) {
    Write-Host "== $Message" -ForegroundColor Cyan
}

function Write-Note([string] $Message) {
    Write-Host $Message
}

function Assert-Administrator {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this from an elevated PowerShell (Run as administrator).'
    }
    if (-not [Environment]::Is64BitOperatingSystem) {
        throw 'A 64-bit Windows is required.'
    }
}

# Runs a program and returns its output lines. Windows PowerShell 5.1 turns a native program's stderr into errors
# when $ErrorActionPreference is Stop (java -version writes to stderr), so it is relaxed for the call only
function Invoke-Native {
    param([Parameter(Mandatory)] [string] $FilePath, [string[]] $Arguments = @(), [switch] $AllowFailure)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $FilePath @Arguments 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if (-not $AllowFailure -and $code -ne 0) {
        # Only the command, never its arguments: they can hold passwords
        $what = $FilePath
        if ($Arguments.Count -gt 0) { $what = "$FilePath $($Arguments[0])" }
        throw "$what failed with exit code $code`n$($output -join "`n")"
    }
    return , @($output)
}

function New-RandomSecret([int] $Bytes = 24) {
    $buffer = New-Object byte[] $Bytes
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return (($buffer | ForEach-Object { $_.ToString('x2') }) -join '')
}

function ConvertFrom-SecureStringPlain([SecureString] $Value) {
    if (-not $Value) { return '' }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

# UTF-8 without a byte-order mark: Java properties, Keycloak's JSON and WinSW all read it
function Write-TextFile([string] $Path, [string] $Text) {
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding $false))
}

# Java, Keycloak and Spring read forward slashes on Windows; backslashes are escapes in properties files
function ConvertTo-ForwardSlash([string] $Path) {
    return $Path.Replace('\', '/')
}

# ---- Downloads ---------------------------------------------------------------------------------------------------

# Downloads (or copies a file given by the administrator) and checks it against a pinned hash
function Get-VerifiedFile {
    param([string] $Url, [string] $Target, [string] $Sha256, [string] $Sha1, [string] $LocalCopy)
    if ($LocalCopy) {
        Copy-Item -LiteralPath $LocalCopy -Destination $Target -Force
    }
    elseif (-not (Test-Path -LiteralPath $Target)) {
        Write-Note "Downloading $Url"
        $partial = "$Target.partial"
        Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing
        Move-Item -LiteralPath $partial -Destination $Target -Force
    }
    $algorithm = 'SHA256'
    $expected = $Sha256
    if ($Sha1) { $algorithm = 'SHA1'; $expected = $Sha1 }
    $actual = (Get-FileHash -LiteralPath $Target -Algorithm $algorithm).Hash
    if ($actual -ne $expected.ToUpperInvariant()) {
        Remove-Item -LiteralPath $Target -Force
        throw "Checksum mismatch for $Target (expected $algorithm $expected). Download it again, or pass the right file."
    }
}

function Get-DownloadDirectory([string] $InstallRoot) {
    $dir = Join-Path $InstallRoot 'downloads'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return $dir
}

# ---- Java 21 -----------------------------------------------------------------------------------------------------

function Get-JavaMajor([string] $Java) {
    $lines = Invoke-Native -FilePath $Java -Arguments @('-XshowSettings:properties', '-version') -AllowFailure
    foreach ($line in $lines) {
        if ($line -match 'java\.specification\.version = (\d+)') { return [int] $Matches[1] }
    }
    return 0
}

# The first Java 21 or newer: JAVA_HOME, the PATH, the usual vendor folders, then the one this script installs
function Find-Java21([string] $InstallRoot) {
    $candidates = New-Object Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add($onPath.Source) }
    $patterns = @(
        (Join-Path $InstallRoot 'java\*\bin\java.exe'),
        "$env:ProgramFiles\Eclipse Adoptium\*\bin\java.exe",
        "$env:ProgramFiles\Microsoft\jdk-*\bin\java.exe",
        "$env:ProgramFiles\Amazon Corretto\*\bin\java.exe",
        "$env:ProgramFiles\Java\*\bin\java.exe",
        "$env:ProgramFiles\Zulu\*\bin\java.exe"
    )
    foreach ($pattern in $patterns) {
        Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | ForEach-Object { $candidates.Add($_.FullName) }
    }
    foreach ($candidate in $candidates) {
        if ((Test-Path -LiteralPath $candidate) -and (Get-JavaMajor $candidate) -ge 21) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

# Returns java.exe of a Java 21; installs Temurin under <InstallRoot>\java when none is found
function Install-Java21 {
    param([string] $InstallRoot, [string] $JavaArchive, [switch] $NoDeps)
    $java = Find-Java21 $InstallRoot
    if ($java) { Write-Note "Java 21: $java"; return $java }
    if ($NoDeps) { throw 'Java 21 not found. Install it, or run without -NoDeps.' }
    Write-Note "No Java 21 found; installing Eclipse Temurin $($script:Temurin.Release) in $InstallRoot\java"
    $zip = Join-Path (Get-DownloadDirectory $InstallRoot) 'temurin-21-jre.zip'
    Get-VerifiedFile -Url $script:Temurin.Url -Target $zip -Sha256 $script:Temurin.Sha256 -LocalCopy $JavaArchive
    $javaRoot = Join-Path $InstallRoot 'java'
    New-Item -ItemType Directory -Force -Path $javaRoot | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $javaRoot -Force
    $java = Find-Java21 $InstallRoot
    if (-not $java) { throw "Java 21 could not be installed from $zip" }
    Write-Note "Java 21: $java"
    return $java
}

function Get-JavaHome([string] $Java) {
    return (Split-Path (Split-Path $Java -Parent) -Parent)
}

# A trust store with Java's own CAs plus one more certificate (self-signed, or an internal CA), so the viewer and
# Keycloak's admin tool trust the Keycloak site while still trusting the public CAs of the repository
function New-JavaTrustStore([string] $Java, [string] $Certificate, [string] $Target) {
    $javaHome = Get-JavaHome $Java
    $keytool = Join-Path $javaHome 'bin\keytool.exe'
    $cacerts = Join-Path $javaHome 'lib\security\cacerts'
    if (-not (Test-Path $keytool) -or -not (Test-Path $cacerts)) { throw "keytool or cacerts not found under $javaHome" }
    if (Test-Path -LiteralPath $Target) { Remove-Item -LiteralPath $Target -Force }
    Invoke-Native -FilePath $keytool -Arguments @('-importkeystore', '-noprompt', '-srckeystore', $cacerts, '-srcstorepass', 'changeit',
        '-destkeystore', $Target, '-deststoretype', 'PKCS12', '-deststorepass', 'changeit') | Out-Null
    Invoke-Native -FilePath $keytool -Arguments @('-importcert', '-noprompt', '-alias', 'sapiensml-extra-trust', '-file', $Certificate,
        '-keystore', $Target, '-storepass', 'changeit') | Out-Null
}

# ---- Certificates ------------------------------------------------------------------------------------------------

# A self-signed certificate for the given names (browsers warn: test servers only), exported as a .pfx for the
# viewer and Keycloak and as a .cer for trust stores. Returns @{ Pfx; Cer; Password }
function New-SelfSignedPfx([string[]] $DnsNames, [string] $Directory) {
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    # Administrators only before anything is written; the services are given read access once they exist
    Protect-Path -Path $Directory -Readers @()
    $pfx = Join-Path $Directory 'self-signed.pfx'
    $cer = Join-Path $Directory 'self-signed.cer'
    $passwordFile = Join-Path $Directory 'self-signed.password'
    if ((Test-Path $pfx) -and (Test-Path $cer) -and (Test-Path $passwordFile)) {
        Write-Note "Using the existing self-signed certificate $pfx"
        return @{ Pfx = $pfx; Cer = $cer; Password = (Get-Content -LiteralPath $passwordFile -Raw).Trim() }
    }
    $cert = New-SelfSignedCertificate -DnsName $DnsNames -CertStoreLocation 'Cert:\LocalMachine\My' -KeyExportPolicy Exportable `
        -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 -NotAfter (Get-Date).AddDays(825) -FriendlyName 'SapiensML Document Viewer (self-signed)'
    $password = New-RandomSecret 18
    $secure = ConvertTo-SecureString -String $password -AsPlainText -Force
    Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $secure | Out-Null
    Export-Certificate -Cert $cert -FilePath $cer | Out-Null
    Write-TextFile $passwordFile $password
    Write-Note "Created a self-signed certificate for $($DnsNames -join ', ') ($pfx); browsers will warn about it"
    return @{ Pfx = $pfx; Cer = $cer; Password = $password }
}

# ---- Permissions, firewall, hosts --------------------------------------------------------------------------------

# Owner-only access: Administrators and SYSTEM full, the given accounts read (or modify), nothing inherited
function Protect-Path {
    param([string] $Path, [string[]] $Readers = @(), [string[]] $Writers = @())
    $folder = Test-Path -LiteralPath $Path -PathType Container
    $inherit = ''
    if ($folder) { $inherit = '(OI)(CI)' }
    # /grant:r replaces the listed accounts' entries only, so calls for different accounts add up
    $grants = @('/inheritance:r', '/grant:r', "$($script:Administrators):$($inherit)F", "$($script:LocalSystem):$($inherit)F")
    foreach ($account in $Readers) { $grants += "$($account):$($inherit)RX" }
    foreach ($account in $Writers) { $grants += "$($account):$($inherit)M" }
    Invoke-Native -FilePath 'icacls.exe' -Arguments (@($Path) + $grants + @('/Q')) | Out-Null
}

# Fails early when another program (often IIS's Default Web Site on 443) already listens on the port
function Assert-PortFree([int] $Port, [string] $ServiceId) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return }
    $ours = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
    if ($ours -and $ours.Status -eq 'Running') { return }
    $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    $name = 'another program'
    if ($process) { $name = "$($process.ProcessName) (process $($process.Id))" }
    throw "Port $Port is already used by $name. Free it (for IIS: remove the site's https binding) or choose another port."
}

function Open-FirewallPort([string] $Name, [int] $Port) {
    if (-not (Get-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $Name -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any | Out-Null
        Write-Note "Windows Firewall: port $Port open ($Name)"
    }
}

# For test servers without DNS records: the names resolve to this server
function Add-HostsEntry([string] $HostName) {
    $hosts = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    $pattern = '^\s*127\.0\.0\.1\s+' + [Regex]::Escape($HostName) + '(\s|$)'
    if (-not (Select-String -LiteralPath $hosts -Pattern $pattern -Quiet)) {
        Add-Content -LiteralPath $hosts -Value "127.0.0.1 $HostName # sapiensml-document-viewer" -Encoding ASCII
        Write-Note "Added $HostName to $hosts"
    }
}

function Test-Resolves([string] $HostName) {
    try { [Net.Dns]::GetHostAddresses($HostName) | Out-Null; return $true } catch { return $false }
}

# ---- Windows services (WinSW) ------------------------------------------------------------------------------------

# Puts WinSW next to the service's XML as <id>.exe, the layout WinSW expects
function Install-ServiceWrapper([string] $InstallRoot, [string] $Directory, [string] $Id, [string] $WinswExe) {
    $target = Join-Path $Directory "$Id.exe"
    if (-not (Test-Path -LiteralPath $target)) {
        $download = Join-Path (Get-DownloadDirectory $InstallRoot) 'WinSW-x64.exe'
        Get-VerifiedFile -Url $script:WinSW.Url -Target $download -Sha256 $script:WinSW.Sha256 -LocalCopy $WinswExe
        Copy-Item -LiteralPath $download -Destination $target -Force
    }
    return $target
}

function ConvertTo-XmlText([string] $Value) {
    return [Security.SecurityElement]::Escape($Value)
}

# WinSW service definition. Env values and arguments are escaped here
function New-ServiceXml {
    param([string] $Id, [string] $Name, [string] $Description, [string] $Executable, [string] $Arguments,
          [string] $WorkingDirectory, [string] $LogDirectory, [hashtable] $Environment = @{}, [string[]] $DependsOn = @())
    $envXml = ($Environment.Keys | Sort-Object | ForEach-Object {
        "  <env name=`"$(ConvertTo-XmlText $_)`" value=`"$(ConvertTo-XmlText ([string] $Environment[$_]))`"/>"
    }) -join "`r`n"
    $dependXml = ($DependsOn | ForEach-Object { "  <depend>$(ConvertTo-XmlText $_)</depend>" }) -join "`r`n"
    return @"
<!-- Written by the SapiensML installer; changes are overwritten on the next install or upgrade -->
<service>
  <id>$(ConvertTo-XmlText $Id)</id>
  <name>$(ConvertTo-XmlText $Name)</name>
  <description>$(ConvertTo-XmlText $Description)</description>
  <executable>$(ConvertTo-XmlText $Executable)</executable>
  <arguments>$(ConvertTo-XmlText $Arguments)</arguments>
  <workingdirectory>$(ConvertTo-XmlText $WorkingDirectory)</workingdirectory>
$envXml
$dependXml
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <stoptimeout>45 sec</stoptimeout>
  <onfailure action="restart" delay="10 sec"/>
  <onfailure action="restart" delay="30 sec"/>
  <onfailure action="restart" delay="60 sec"/>
  <resetfailure>1 hour</resetfailure>
  <logpath>$(ConvertTo-XmlText $LogDirectory)</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>20480</sizeThreshold>
    <keepFiles>10</keepFiles>
  </log>
</service>
"@
}

# Registers the service once, then makes it run as its own virtual account (NT SERVICE\<id>), not LocalSystem
function Register-WrappedService([string] $Wrapper, [string] $Id) {
    if (-not (Get-Service -Name $Id -ErrorAction SilentlyContinue)) {
        Invoke-Native -FilePath $Wrapper -Arguments @('install') | Out-Null
    }
    $service = Get-CimInstance -ClassName Win32_Service -Filter "Name='$Id'"
    $account = "NT SERVICE\$Id"
    if ($service.StartName -ne $account) {
        $result = Invoke-CimMethod -InputObject $service -MethodName Change -Arguments @{ StartName = $account; StartPassword = '' }
        if ($result.ReturnValue -ne 0) { throw "Could not make $Id run as $account (error $($result.ReturnValue))" }
    }
    return $account
}

function Wait-HttpOk([string] $Url, [int] $TimeoutSeconds = 180, [string] $Expect = 'UP') {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and $response.Content -match $Expect) { return $true }
        }
        catch {
            # Not listening yet
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

# ---- PostgreSQL for Keycloak -------------------------------------------------------------------------------------

function Find-PostgresBin {
    $psql = Get-ChildItem -Path "$env:ProgramFiles\PostgreSQL\*\bin\psql.exe" -ErrorAction SilentlyContinue |
        Sort-Object { [int] ($_.Directory.Parent.Name -replace '\D', '') } -Descending | Select-Object -First 1
    if ($psql) { return $psql.DirectoryName }
    return $null
}

# Installs PostgreSQL 17 with EnterpriseDB's silent installer when none is installed. Returns the superuser password
# (generated for a new installation, read from -SuperuserPasswordFile for an existing one)
function Install-Postgres {
    param([string] $InstallRoot, [string] $DataRoot, [string] $Installer, [string] $SuperuserPasswordFile, [switch] $NoDeps)
    $passwordFile = Join-Path $DataRoot 'postgres\superuser.password'
    $bin = Find-PostgresBin
    if ($bin) {
        if ($SuperuserPasswordFile) { return (Get-Content -LiteralPath $SuperuserPasswordFile -Raw).Trim() }
        if (Test-Path -LiteralPath $passwordFile) { return (Get-Content -LiteralPath $passwordFile -Raw).Trim() }
        throw "PostgreSQL is already installed ($bin). Pass its superuser password with -PostgresSuperuserPasswordFile."
    }
    if ($NoDeps) { throw 'PostgreSQL is not installed (run without -NoDeps to install it).' }
    $setup = Join-Path (Get-DownloadDirectory $InstallRoot) 'postgresql-windows-x64.exe'
    Get-VerifiedFile -Url $script:PostgresRelease.Url -Target $setup -Sha256 $script:PostgresRelease.Sha256 -LocalCopy $Installer
    $password = New-RandomSecret 18
    $major = $script:PostgresRelease.Major
    $prefix = "$env:ProgramFiles\PostgreSQL\$major"
    Write-Note "Installing PostgreSQL $major (this takes a few minutes)"
    $process = Start-Process -FilePath $setup -Wait -PassThru -ArgumentList @('--mode', 'unattended', '--unattendedmodeui', 'none',
        '--superpassword', $password, '--servicename', "postgresql-x64-$major", '--serverport', '5432',
        '--prefix', "`"$prefix`"", '--datadir', "`"$prefix\data`"", '--disable-components', 'pgAdmin,stackbuilder')
    if ($process.ExitCode -ne 0) { throw "The PostgreSQL installer failed with exit code $($process.ExitCode)." }
    New-Item -ItemType Directory -Force -Path (Split-Path $passwordFile) | Out-Null
    Protect-Path -Path (Split-Path $passwordFile) -Readers @()
    Write-TextFile $passwordFile $password
    $bin = Join-Path $prefix 'bin'
    # Local connections only; the installer listens on every address by default
    Invoke-Psql -Bin $bin -Password $password -Sql "ALTER SYSTEM SET listen_addresses = 'localhost'" | Out-Null
    Restart-Service -Name "postgresql-x64-$major"
    Start-Sleep -Seconds 3
    Write-Note "PostgreSQL $major installed; superuser password in $passwordFile (administrators only)"
    return $password
}

function Invoke-Psql([string] $Bin, [string] $Password, [string] $Sql) {
    $env:PGPASSWORD = $Password
    try {
        return Invoke-Native -FilePath (Join-Path $Bin 'psql.exe') -Arguments @('-h', '127.0.0.1', '-U', 'postgres', '-d', 'postgres',
            '-v', 'ON_ERROR_STOP=1', '-tAq', '-c', $Sql)
    }
    finally {
        Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
    }
}

# Database and login role; names are fixed words, the password is hex
function New-PostgresDatabase([string] $SuperuserPassword, [string] $Name, [string] $User, [string] $Password) {
    $bin = Find-PostgresBin
    if (-not (Invoke-Psql -Bin $bin -Password $SuperuserPassword -Sql "SELECT 1 FROM pg_roles WHERE rolname = '$User'")) {
        Invoke-Psql -Bin $bin -Password $SuperuserPassword -Sql "CREATE ROLE $User LOGIN PASSWORD '$Password'" | Out-Null
    }
    else {
        Invoke-Psql -Bin $bin -Password $SuperuserPassword -Sql "ALTER ROLE $User WITH LOGIN PASSWORD '$Password'" | Out-Null
    }
    if (-not (Invoke-Psql -Bin $bin -Password $SuperuserPassword -Sql "SELECT 1 FROM pg_database WHERE datname = '$Name'")) {
        Invoke-Psql -Bin $bin -Password $SuperuserPassword -Sql "CREATE DATABASE $Name OWNER $User ENCODING 'UTF8' TEMPLATE template0" | Out-Null
    }
}

# ---- Settings files (Java properties) -------------------------------------------------------------------------------

function Get-PropertiesValue([string] $Path, [string] $Key, [string] $Default = '') {
    if (-not (Test-Path -LiteralPath $Path)) { return $Default }
    $value = $null
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line.StartsWith("$Key=")) { $value = $line.Substring($Key.Length + 1).Replace('\\', '\') }
    }
    if ([string]::IsNullOrEmpty($value)) { return $Default }
    return $value
}

# Sets KEY=value (adds it when missing); backslashes are escaped as Java properties need
function Set-PropertiesValue([string] $Path, [string] $Key, [string] $Value) {
    $escaped = $Value.Replace('\', '\\')
    $lines = New-Object Collections.Generic.List[string]
    $done = $false
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line.StartsWith("$Key=") -or $line.StartsWith("#$Key=")) {
            if (-not $done) { $lines.Add("$Key=$escaped"); $done = $true }
        }
        else {
            $lines.Add($line)
        }
    }
    if (-not $done) { $lines.Add("$Key=$escaped") }
    Write-TextFile $Path (($lines -join "`r`n") + "`r`n")
}
