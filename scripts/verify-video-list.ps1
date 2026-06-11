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

$publicVideos = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos?pageNo=1&pageSize=5" -Headers $headers
$myVideos = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/my?pageNo=1&pageSize=5" -Headers $headers

[PSCustomObject]@{
    PublicVideos = $publicVideos
    MyVideos = $myVideos
} | ConvertTo-Json -Depth 10
