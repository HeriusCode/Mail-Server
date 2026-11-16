param()
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $demoRoot = (Get-Location).Path
    if (-not (Test-Path target/classes/MailServer/MailServerGUI.class) -or
        -not (Test-Path target/demo-lib/jakarta.mail-2.0.3.jar)) {
        throw 'Build first: powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build-Demo.ps1'
    }
    $smtpPort = 2525
    $popPort = 1110
    if ($env:MAIL_SMTP_PORT) { $smtpPort = [int]$env:MAIL_SMTP_PORT }
    if ($env:MAIL_POP3_PORT) { $popPort = [int]$env:MAIL_POP3_PORT }
    foreach ($port in @($smtpPort, $popPort)) {
        if ($port -lt 1 -or $port -gt 65535) { throw 'Protocol port must be between 1 and 65535' }
        $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        try { $probe.Start() } catch { throw "TCP port $port is busy. Close the previous server or choose another protocol port." }
        finally { $probe.Stop() }
    }
    $probeUdp = [Net.Sockets.UdpClient]::new()
    try { $probeUdp.Client.ExclusiveAddressUse = $true; $probeUdp.Client.Bind([Net.IPEndPoint]::new([Net.IPAddress]::Loopback,5000)) }
    catch { throw 'UDP port 5000 is busy. Close the previous server before starting another demo.' }
    finally { $probeUdp.Dispose() }

    # Use the running JDK directly instead of Oracle's javapath forwarding executable.
    $previousErrorPolicy = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $settings = & java -XshowSettings:properties -version 2>&1 | ForEach-Object { $_.ToString() }
    } finally { $ErrorActionPreference = $previousErrorPolicy }
    $javaHomeLine = $settings | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
    if (-not $javaHomeLine) { throw 'Could not determine the installed Java home' }
    $javaw = Join-Path ($javaHomeLine -replace '^\s*java\.home\s*=\s*','') 'bin/javaw.exe'
    if (-not (Test-Path -LiteralPath $javaw)) { throw 'javaw.exe missing from the installed JDK' }
    New-Item -ItemType Directory -Force out,evidence | Out-Null
    $launched = [Collections.Generic.List[Diagnostics.Process]]::new()
    function Start-MailWindow([string]$class, [string]$name, [switch]$AutoStart) {
        $arguments = @('-cp','"target/classes;target/demo-lib/*"',$class)
        if ($AutoStart) { $arguments += '--start' }
        # These three windows are the interactive demo requested by the user.
        $process = Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $demoRoot -WindowStyle Normal -PassThru `
            -RedirectStandardError (Join-Path $demoRoot "out/$name.stderr.log")
        $launched.Add($process)
        return $process
    }
    function Test-Banner([int]$port, [string]$prefix) {
        $socket = [Net.Sockets.TcpClient]::new()
        try {
            $connect = $socket.ConnectAsync('127.0.0.1',$port)
            if (-not $connect.Wait(300)) { return $false }
            $stream = $socket.GetStream(); $stream.ReadTimeout = 500
            $reader = [IO.StreamReader]::new($stream)
            return $reader.ReadLine().StartsWith($prefix)
        } catch { return $false } finally { $socket.Dispose() }
    }
    function Test-UdpPing {
        $socket = [Net.Sockets.UdpClient]::new()
        try {
            $socket.Connect('127.0.0.1',5000); $socket.Client.ReceiveTimeout = 500
            $id = [Guid]::NewGuid().ToString(); $bytes = [Text.Encoding]::UTF8.GetBytes("$id|PING")
            [void]$socket.Send($bytes,$bytes.Length)
            $remote = [Net.IPEndPoint]::new([Net.IPAddress]::Any,0)
            return [Text.Encoding]::UTF8.GetString($socket.Receive([ref]$remote)) -eq "$id|PONG"
        } catch { return $false } finally { $socket.Dispose() }
    }
    try {
        $server = Start-MailWindow 'MailServer.MailServerGUI' 'demo-server' -AutoStart
        $deadline = [DateTime]::UtcNow.AddSeconds(15); $ready = $false
        do {
            $server.Refresh()
            if ($server.HasExited) { throw 'Server exited; inspect out/demo-server.stderr.log' }
            $ready = (Test-UdpPing) -and (Test-Banner $smtpPort '220 localhost MailFlow SMTP') -and (Test-Banner $popPort '+OK MailFlow POP3')
            if (-not $ready) { Start-Sleep -Milliseconds 100 }
        } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
        if (-not $ready) { throw 'Server did not become ready; inspect its log window and out/demo-server.stderr.log' }
        $clientA = Start-MailWindow 'MailClient.MailClientGUI' 'demo-client-a'
        $clientB = Start-MailWindow 'MailClient.MailClientGUI' 'demo-client-b'
        foreach ($process in $launched) {
            $deadline = [DateTime]::UtcNow.AddSeconds(10)
            do {
                $process.Refresh()
                if ($process.HasExited) { throw 'A demo window exited; inspect out/demo-*.stderr.log' }
                if ($process.MainWindowHandle -eq 0) { Start-Sleep -Milliseconds 100 }
            } while ($process.MainWindowHandle -eq 0 -and [DateTime]::UtcNow -lt $deadline)
            if ($process.MainWindowHandle -eq 0) { throw 'A demo window was not created in time' }
        }
        $windows = @($launched | ForEach-Object { @{ pid=$_.Id; title=$_.MainWindowTitle; started=$_.StartTime.ToUniversalTime().ToString('o') } })
        $windows | ConvertTo-Json | Set-Content -Encoding UTF8 out/demo-processes.json
        @('PASS: server window and two client windows launched.',
          'PASS: UDP 127.0.0.1:5000 returned a matching request ID and PONG.',
          "PASS: SMTP 127.0.0.1:$smtpPort returned the MailFlow SMTP greeting.",
          "PASS: POP3 127.0.0.1:$popPort returned the MailFlow POP3 greeting.",
          ($windows | ForEach-Object { "Window PID=$($_.pid); title=$($_.title)" }),
          'No accounts were created or real emails sent by this launcher.') | Set-Content -Encoding UTF8 evidence/demo-launch.txt
        Write-Output 'Ready: Server is running; two Mail Client windows are open. Register/login to demonstrate mail delivery.'
    } catch {
        # Roll back only processes started by this invocation, never a pre-existing application.
        foreach ($process in $launched) {
            $process.Refresh()
            if (-not $process.HasExited) {
                [void]$process.CloseMainWindow()
                if (-not $process.WaitForExit(3000)) { $process.Kill() }
            }
        }
        throw
    }
} finally { Pop-Location }
