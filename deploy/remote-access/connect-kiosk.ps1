param(
    [string]$SshUser = 'o',
    [string]$Server = '100.122.121.36',
    [int]$LocalPort = 15900
)

$ErrorActionPreference = 'Stop'
$ssh = Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe'
$viewer = Join-Path $env:ProgramFiles 'TigerVNC\vncviewer.exe'
if (-not (Test-Path -LiteralPath $ssh)) { throw 'Windows OpenSSH client is not installed.' }
if (-not (Test-Path -LiteralPath $viewer)) { throw 'Install TigerVNC Viewer first.' }
if ($LocalPort -lt 1024 -or $LocalPort -gt 65535) { throw 'Invalid local port.' }
if ($SshUser -notmatch '^[a-zA-Z0-9_.-]+$' -or $Server -notmatch '^[a-zA-Z0-9_.:-]+$') {
    throw 'Invalid SSH user or server.'
}

# Avoid connecting the viewer to an unrelated process on an occupied port.
$listener = Get-NetTCPConnection -State Listen -LocalPort $LocalPort -ErrorAction SilentlyContinue
if ($listener) { throw "Port $LocalPort is already in use. Close the old tunnel or use -LocalPort 15901." }

Write-Host 'Enter the SSH password in the new terminal. Keep it open while using VNC.'
$sshArgs = @('-N', '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=30',
    '-o', 'ServerAliveCountMax=3', '-L', "127.0.0.1:${LocalPort}:127.0.0.1:5900", "${SshUser}@${Server}")
# The visible terminal is required for SSH password / host-key prompts.
$tunnel = Start-Process -FilePath $ssh -ArgumentList $sshArgs -PassThru
Write-Host 'Waiting for the SSH tunnel (up to 2 minutes)...'
$ready = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    $tunnel.Refresh()
    if ($tunnel.HasExited) { throw 'SSH exited. Check the message in the SSH terminal.' }
    if (Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort $LocalPort -ErrorAction SilentlyContinue) {
        $ready = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Tunnel not ready. Complete SSH login, then open kiosk.tigervnc manually (default port only).' }
Start-Process -FilePath $viewer -ArgumentList "127.0.0.1::$LocalPort"
Write-Host 'Viewer opened. Close the SSH terminal when finished to stop the tunnel.'
