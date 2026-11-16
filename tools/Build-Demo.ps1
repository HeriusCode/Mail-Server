param([switch]$Offline, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $demoRoot = (Get-Location).Path
    # Clean only generated classes/reports/runtime JARs; preserve mail_storage and test fixtures.
    foreach ($relative in @('target/classes','target/test-classes','target/surefire-reports','target/demo-lib','target/lib')) {
        $generated = [IO.Path]::GetFullPath((Join-Path $demoRoot $relative))
        if (-not $generated.StartsWith($demoRoot + '\target\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe generated path' }
        if (Test-Path -LiteralPath $generated) { Remove-Item -LiteralPath $generated -Recurse -Force }
    }
    New-Item -ItemType Directory -Force evidence,out/audit | Out-Null
    $arguments = @('-Dmaven.repo.local=out/m2','-B','test','dependency:copy-dependencies','-DincludeScope=runtime','-DoutputDirectory=target/demo-lib')
    if ($Offline) { $arguments = @('-o') + $arguments }
    if ($SkipTests) { $arguments += '-DskipTests' }
    & mvn @arguments 2>&1 | Tee-Object -FilePath evidence/demo-build.txt
    if ($LASTEXITCODE -ne 0) { throw 'Build/tests failed; see evidence/demo-build.txt' }
    if (-not $SkipTests) {
        & javac -encoding UTF-8 -cp 'target/classes;target/demo-lib/*' -d out/audit tools/audit/Step01Audit.java
        if ($LASTEXITCODE -ne 0) { throw 'Regression harness failed to compile' }
        $regression = & java '-Dstdout.encoding=UTF-8' -cp 'out/audit;target/classes;target/demo-lib/*' Step01Audit
        $regression | Set-Content -Encoding UTF8 evidence/demo-regression.txt
        if ($LASTEXITCODE -ne 0 -or $regression -notcontains 'TOTAL PASS=22 FAIL=0') { throw 'Regression expectations failed' }
        $evidence = @('File Storage demo: executed tests. No MySQL/JDBC checks are run.','STEP 01 regression: 22 PASS / 0 FAIL','')
        foreach ($report in Get-ChildItem target/surefire-reports -Filter '*.txt' | Sort-Object Name) {
            $evidence += "--- $($report.Name) ---"
            $evidence += Get-Content -Encoding UTF8 -LiteralPath $report.FullName
        }
        $evidence += 'Gmail delivery: NOT RUN unless SMTP credentials and an authorized test recipient are explicitly configured.'
        $evidence | Set-Content -Encoding UTF8 evidence/demo-runtime.txt
    }
    Write-Output 'Ready: java -cp "target/classes;target/demo-lib/*" MailServer.MailServerGUI'
} finally { Pop-Location }
