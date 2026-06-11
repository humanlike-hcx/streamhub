param(
    [long]$VideoId = 11
)

$ErrorActionPreference = "Stop"

$baseUrl = "http://localhost:8080"
$username = "uploadtester"
$password = "Password123"

function Send-Text([System.Net.WebSockets.ClientWebSocket]$Socket, [string]$Text) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $segment = [ArraySegment[byte]]::new($bytes)
    [void]$Socket.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

function Connect-WebSocket([System.Net.WebSockets.ClientWebSocket]$Socket, [Uri]$Uri) {
    $source = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(5))
    try {
        [void]$Socket.ConnectAsync($Uri, $source.Token).GetAwaiter().GetResult()
    }
    finally {
        $source.Dispose()
    }
}

function Receive-Text([System.Net.WebSockets.ClientWebSocket]$Socket) {
    $buffer = New-Object byte[] 4096
    $segment = [ArraySegment[byte]]::new($buffer)
    $source = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(5))
    try {
        $result = $Socket.ReceiveAsync($segment, $source.Token).GetAwaiter().GetResult()
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            throw "WebSocket closed unexpectedly"
        }
        return [System.Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
    }
    finally {
        $source.Dispose()
    }
}

$loginBody = @{
    username = $username
    password = $password
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType "application/json" -Body $loginBody
$token = [Uri]::EscapeDataString($loginResponse.data.token)
$uri = [Uri]"ws://localhost:8090/ws/danmaku?videoId=$VideoId&token=$token"

$clientA = [System.Net.WebSockets.ClientWebSocket]::new()
$clientB = [System.Net.WebSockets.ClientWebSocket]::new()

try {
    Connect-WebSocket $clientA $uri
    Connect-WebSocket $clientB $uri

    Send-Text $clientA '{"type":"PING"}'
    $pong = Receive-Text $clientA

    Send-Text $clientA '{"type":"DANMAKU","content":"hello danmaku"}'
    $receivedByA = Receive-Text $clientA
    $receivedByB = Receive-Text $clientB

    [PSCustomObject]@{
        videoId = $VideoId
        clientAState = $clientA.State.ToString()
        clientBState = $clientB.State.ToString()
        pong = $pong
        receivedByA = $receivedByA
        receivedByB = $receivedByB
    } | ConvertTo-Json -Depth 4
}
finally {
    if ($clientA.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        [void]$clientA.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    }
    if ($clientB.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        [void]$clientB.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    }
    $clientA.Dispose()
    $clientB.Dispose()
}
