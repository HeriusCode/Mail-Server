param([switch]$Offline, [switch]$UseExistingBuild)

# Run from a normal PowerShell terminal. Cross-process UDP tests need localhost networking.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Push-Location (Join-Path $PSScriptRoot '..')
try {
    New-Item -ItemType Directory -Force evidence,out/audit | Out-Null
    $mavenArguments = @('-Dmaven.repo.local=out/m2', '-B', 'test', 'dependency:copy-dependencies', '-DincludeScope=runtime', '-DoutputDirectory=target/lib')
    if ($Offline) { $mavenArguments = @('-o') + $mavenArguments }
    if (-not $UseExistingBuild) {
        & mvn @mavenArguments 2>&1 | Tee-Object -FilePath evidence/step02-maven.txt
        if ($LASTEXITCODE -ne 0) { throw 'Maven build/tests failed; inspect evidence/step02-maven.txt.' }
    }
    $runtimeClasspath = 'target/classes;target/lib/*'
    & javac -encoding UTF-8 -cp $runtimeClasspath -d out/audit tools/audit/Step01Audit.java
    if ($LASTEXITCODE -ne 0) { throw 'Audit harness compilation failed.' }
    $regression = & java '-Dstdout.encoding=UTF-8' -cp "out/audit;$runtimeClasspath" Step01Audit
    $regression | Set-Content -Encoding UTF8 evidence/step02-step01-regression.txt
    if ($LASTEXITCODE -ne 0 -or $regression -notcontains 'TOTAL PASS=22 FAIL=0') { throw 'STEP 01 regression expectations failed.' }

    $runtime = @('STEP 02 evidence; actual executed tests only.', 'STEP 01 regression: TOTAL PASS=22 FAIL=0', '')
    foreach ($report in Get-ChildItem target/surefire-reports -Filter '*.txt' | Sort-Object Name) {
        $runtime += "--- $($report.Name) ---"
        $runtime += Get-Content -Encoding UTF8 -LiteralPath $report.FullName
    }
    $runtime += '--- Legacy inspection (no writes) ---'
    $runtime += (& java -cp $runtimeClasspath MailServer.DatabaseTool inspect mail_storage 2>&1 | ForEach-Object { $_.ToString() })
    $runtime += '--- Production MySQL connection check ---'
    $runtime += (& java -cp $runtimeClasspath MailServer.DatabaseTool check 2>&1 | ForEach-Object { $_.ToString() })
    $runtime += "DatabaseTool check exit code: $LASTEXITCODE (2 = missing config, NOT RUN)"
    $runtime | Set-Content -Encoding UTF8 evidence/step02-runtime.txt
    Write-Output 'Evidence written. SKIPPED/NOT RUN MySQL checks are not PASS.'
} finally { Pop-Location }
