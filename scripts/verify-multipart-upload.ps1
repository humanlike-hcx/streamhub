$ErrorActionPreference = "Stop"

$baseUrl = "http://localhost:8080"
$username = "uploadtester"
$password = "Password123"

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

$targetDir = Join-Path (Split-Path $PSScriptRoot -Parent) "target"
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
$sampleFile = Join-Path $targetDir "sample-multipart.mp4"
$chunk0 = Join-Path $targetDir "sample-multipart-0.part"
$chunk1 = Join-Path $targetDir "sample-multipart-1.part"
$ffmpegPath = "C:\Users\Probscray\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.1-full_build\bin\ffmpeg.exe"

if (Test-Path $ffmpegPath) {
    & $ffmpegPath -y `
        -f lavfi -i "testsrc=size=320x180:rate=25" `
        -f lavfi -i "sine=frequency=1000:sample_rate=44100" `
        -t 2 `
        -pix_fmt yuv420p `
        -c:v libx264 `
        -c:a aac `
        $sampleFile | Out-Null
}
else {
    Set-Content -Path $sampleFile -Value "streamhub multipart upload verification" -Encoding ascii
}

$bytes = [System.IO.File]::ReadAllBytes($sampleFile)
$chunkSize = [Math]::Ceiling($bytes.Length / 2)
[System.IO.File]::WriteAllBytes($chunk0, $bytes[0..($chunkSize - 1)])
[System.IO.File]::WriteAllBytes($chunk1, $bytes[$chunkSize..($bytes.Length - 1)])

$fileMd5 = (Get-FileHash -Path $sampleFile -Algorithm MD5).Hash.ToLowerInvariant()
$initBody = @{
    title = "Multipart Verification Video"
    description = "Uploaded by scripts/verify-multipart-upload.ps1"
    fileName = "sample-multipart.mp4"
    fileMd5 = $fileMd5
    fileSize = $bytes.Length
    chunkSize = $chunkSize
    totalChunks = 2
} | ConvertTo-Json

$initResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/api/videos/multipart/init" `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType "application/json" `
    -Body $initBody

if ($initResponse.data.instantUploaded) {
    $initResponse | ConvertTo-Json -Depth 8
    exit 0
}

$uploadId = $initResponse.data.uploadId

curl.exe -s `
    -X POST "$baseUrl/api/videos/multipart/chunk" `
    -H "Authorization: Bearer $token" `
    -F "uploadId=$uploadId" `
    -F "chunkIndex=0" `
    -F "file=@$chunk0;type=application/octet-stream" | Out-Null

curl.exe -s `
    -X POST "$baseUrl/api/videos/multipart/chunk" `
    -H "Authorization: Bearer $token" `
    -F "uploadId=$uploadId" `
    -F "chunkIndex=1" `
    -F "file=@$chunk1;type=application/octet-stream" | Out-Null

$progressResponse = Invoke-RestMethod `
    -Method Get `
    -Uri "$baseUrl/api/videos/multipart/$uploadId" `
    -Headers @{ Authorization = "Bearer $token" }

$completeBody = @{
    uploadId = $uploadId
    title = "Multipart Verification Video"
    description = "Merged by multipart upload"
} | ConvertTo-Json

$completeResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/api/videos/multipart/complete" `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType "application/json" `
    -Body $completeBody

$instantResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/api/videos/multipart/init" `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType "application/json" `
    -Body $initBody

@{
    uploadId = $uploadId
    uploadedChunks = $progressResponse.data.uploadedChunks
    completedVideoId = $completeResponse.data.id
    instantUploaded = $instantResponse.data.instantUploaded
    instantVideoId = $instantResponse.data.video.id
} | ConvertTo-Json -Depth 8
