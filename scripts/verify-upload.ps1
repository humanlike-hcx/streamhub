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
$sampleFile = Join-Path $targetDir "sample-upload.mp4"
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
    Set-Content -Path $sampleFile -Value "streamhub upload verification" -Encoding ascii
}

$uploadResponse = curl.exe -s `
    -X POST "$baseUrl/api/videos/upload" `
    -H "Authorization: Bearer $token" `
    -F "title=Verification Video" `
    -F "description=Uploaded by scripts/verify-upload.ps1" `
    -F "file=@$sampleFile;type=video/mp4"

$uploadResponse
