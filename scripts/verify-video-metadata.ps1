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

$detail = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/$VideoId" -Headers $headers
$list = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos?pageNo=1&pageSize=5" -Headers $headers
$cover = Invoke-WebRequest -Method Get -Uri "$baseUrl/api/videos/$VideoId/cover" -Headers $headers -UseBasicParsing

[PSCustomObject]@{
    detailCode = $detail.code
    detailCoverUrl = $detail.data.coverUrl
    detailDuration = $detail.data.duration
    detailWidth = $detail.data.width
    detailHeight = $detail.data.height
    listFirstCoverUrl = $list.data.records[0].coverUrl
    listFirstDuration = $list.data.records[0].duration
    coverStatus = $cover.StatusCode
    coverContentType = $cover.Headers["Content-Type"]
    coverLength = $cover.RawContentLength
} | ConvertTo-Json -Depth 4
