$ErrorActionPreference = "Stop"

$baseUrl = "http://localhost:8080"
$username = "uploadtester"
$password = "Password123"
$videoId = 7

$loginBody = @{
    username = $username
    password = $password
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType "application/json" -Body $loginBody
$token = $loginResponse.data.token
$headers = @{
    Authorization = "Bearer $token"
}

$playInfo = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/$videoId/play" -Headers $headers
$playlist = Invoke-WebRequest -Method Get -Uri "$baseUrl/api/videos/$videoId/hls/master.m3u8" -Headers $headers -UseBasicParsing
$playlistText = [System.Text.Encoding]::UTF8.GetString($playlist.Content)

[PSCustomObject]@{
    PlayInfo = $playInfo
    PlaylistStatus = $playlist.StatusCode
    PlaylistContentType = $playlist.Headers["Content-Type"]
    PlaylistPreview = $playlistText.Substring(0, [Math]::Min(120, $playlistText.Length))
} | ConvertTo-Json -Depth 8
