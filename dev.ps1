param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$GradleArgs
)

$JAVA_HOME = "D:\IntelliJ IDEA 2026.1.4\jbr"

$java_exe = Join-Path $JAVA_HOME "bin\java.exe"
if (-not (Test-Path $java_exe)) {
    Write-Host "[ERROR] Java not found: $java_exe" -ForegroundColor Red
    exit 1
}

Write-Host "[OK] JAVA_HOME: $JAVA_HOME" -ForegroundColor Green

$env:JAVA_HOME = $JAVA_HOME

$gradle_cmd = @(".\gradlew.bat") + $GradleArgs
Write-Host "Running: $($gradle_cmd -join ' ')" -ForegroundColor Cyan

& $gradle_cmd[0] @gradle_cmd[1..$gradle_cmd.Length]

exit $LASTEXITCODE
