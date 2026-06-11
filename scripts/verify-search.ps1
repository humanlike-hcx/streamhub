$ErrorActionPreference = "Stop"

$baseUrl = "http://localhost:8080"
$username = "uploadtester"
$password = "Password123"
$keyword = if ($args.Count -gt 0) { $args[0] } else { "Verification" }

$registerBody = @{
    username = $username
    password = $password
    nickname = "Upload Tester"
} | ConvertTo-Json

try {
    Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/register" -ContentType "application/json" -Body $registerBody | Out-Null
}
catch {
    # The user may already exist from a previous verification run.
}

$loginBody = @{
    username = $username
    password = $password
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType "application/json" -Body $loginBody
$token = $loginResponse.data.token

$encodedKeyword = [uri]::EscapeDataString($keyword)
$searchResponse = Invoke-RestMethod `
    -Method Get `
    -Uri "$baseUrl/api/videos/search?keyword=$encodedKeyword&pageNo=1&pageSize=10" `
    -Headers @{ Authorization = "Bearer $token" }

$searchResponse | ConvertTo-Json -Depth 8
