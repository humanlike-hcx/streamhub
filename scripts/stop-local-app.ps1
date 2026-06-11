$ErrorActionPreference = "Stop"

Get-CimInstance Win32_Process |
    Where-Object {
        $_.CommandLine -like "*streamhub*spring-boot:run*" -or
        $_.CommandLine -like "*maven-wrapper.jar*spring-boot:run*"
    } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force
    }

Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object {
        Stop-Process -Id $_ -Force
    }
