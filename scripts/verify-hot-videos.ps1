param(
    [long]$VideoId = 11
)

$ErrorActionPreference = "Stop"

$baseUrl = "http://localhost:8080"
$username = "uploadtester"
$password = "Password123"

$loginBody = @{
    username = $username
    password = $password
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType "application/json" -Body $loginBody
$token = $loginResponse.data.token
$headers = @{
    Authorization = "Bearer $token"
}

$before = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/$VideoId" -Headers $headers

1..3 | ForEach-Object {
    Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/$VideoId/play" -Headers $headers | Out-Null
}

$afterPlay = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/$VideoId" -Headers $headers
$hot = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/hot?pageNo=1&pageSize=5" -Headers $headers

Start-Sleep -Seconds 35
$afterFlush = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/$VideoId" -Headers $headers

[PSCustomObject]@{
    videoId = $VideoId
    beforePlayCount = $before.data.playCount
    afterPlayDetailCount = $afterPlay.data.playCount
    afterFlushDetailCount = $afterFlush.data.playCount
    hotTotal = $hot.data.total
    hotPageNo = $hot.data.pageNo
    hotPageSize = $hot.data.pageSize
    hotFirstVideoId = $hot.data.records[0].id
    hotFirstPlayCount = $hot.data.records[0].playCount
    hotRecordCount = $hot.data.records.Count
} | ConvertTo-Json -Depth 4
