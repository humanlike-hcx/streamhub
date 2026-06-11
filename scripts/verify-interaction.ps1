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

# Start from a stable interaction state for repeatable verification.
try {
    Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/videos/$VideoId/like" -Headers $headers | Out-Null
}
catch {
}
try {
    Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/videos/$VideoId/collect" -Headers $headers | Out-Null
}
catch {
}

$likeOnce = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/videos/$VideoId/like" -Headers $headers
$likeTwice = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/videos/$VideoId/like" -Headers $headers
$collectOnce = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/videos/$VideoId/collect" -Headers $headers
$collectTwice = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/videos/$VideoId/collect" -Headers $headers

$commentBody = @{
    content = "interaction verification comment"
} | ConvertTo-Json
$comment = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/videos/$VideoId/comments" -Headers $headers -ContentType "application/json" -Body $commentBody
$comments = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/videos/$VideoId/comments?pageNo=1&pageSize=5" -Headers $headers

$unlike = Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/videos/$VideoId/like" -Headers $headers
$uncollect = Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/videos/$VideoId/collect" -Headers $headers

[PSCustomObject]@{
    likeOnce = $likeOnce.data
    likeTwice = $likeTwice.data
    collectOnce = $collectOnce.data
    collectTwice = $collectTwice.data
    commentId = $comment.data.id
    commentContent = $comment.data.content
    commentsTotal = $comments.data.total
    unlike = $unlike.data
    uncollect = $uncollect.data
} | ConvertTo-Json -Depth 5
