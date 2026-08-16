param(
    [string]$OutputPath = (Join-Path $PSScriptRoot 'src\main\resources\archive-embeddings-v3.json'),
    [ValidateRange(1, 96)]
    [int]$BatchSize = 64,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$apiKey = $env:JINA_API_KEY
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw 'JINA_API_KEY is not configured.'
}
if ((Test-Path -LiteralPath $OutputPath) -and -not $Force) {
    throw 'The archive embedding resource already exists. Use -Force only after archive content changes.'
}

$archivePath = Join-Path $PSScriptRoot 'src\main\resources\archive.json'
$archive = Get-Content -Raw -Encoding UTF8 -LiteralPath $archivePath | ConvertFrom-Json
$prepared = foreach ($entry in $archive.entries) {
    $text = 'passage=' + [string]$entry.passages.en + "`ncontext=" +
        [string]$entry.englishContextNote + "`nthemes=" +
        (($entry.themes | ForEach-Object { [string]$_ }) -join ',')
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))
    } finally {
        $sha.Dispose()
    }
    [pscustomobject]@{
        id = [long]$entry.id
        contentHash = -join ($hashBytes | ForEach-Object { $_.ToString('x2') })
        text = $text
    }
}

$headers = @{
    Authorization = 'Bearer ' + $apiKey
    'Content-Type' = 'application/json'
}
$generated = [System.Collections.Generic.List[object]]::new()
for ($offset = 0; $offset -lt $prepared.Count; $offset += $BatchSize) {
    $last = [Math]::Min($offset + $BatchSize - 1, $prepared.Count - 1)
    $batch = @($prepared[$offset..$last])
    $body = [ordered]@{
        model = 'jina-embeddings-v3'
        task = 'retrieval.passage'
        dimensions = 1024
        normalized = $true
        embedding_type = 'float'
        late_chunking = $false
        truncate = $true
        input = @($batch | ForEach-Object { $_.text })
    } | ConvertTo-Json -Depth 6 -Compress

    $response = $null
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        try {
            $response = Invoke-RestMethod -Method Post -Uri 'https://api.jina.ai/v1/embeddings' `
                -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
            break
        } catch {
            $statusCode = $null
            if ($null -ne $_.Exception.Response) {
                try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { $statusCode = $null }
            }
            $retryable = $null -eq $statusCode -or $statusCode -eq 429 -or $statusCode -ge 500
            if (-not $retryable -or $attempt -eq 1) {
                $safeStatus = if ($null -eq $statusCode) { 'NETWORK_ERROR' } else { "HTTP_$statusCode" }
                $safeValidation = ''
                if ($statusCode -eq 422 -and $null -ne $_.Exception.Response) {
                    try {
                        $reader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
                        try {
                            $errorJson = $reader.ReadToEnd() | ConvertFrom-Json
                            $safeValidation = @($errorJson.detail | ForEach-Object {
                                '(' + (@($_.loc) -join '.') + ':' + [string]$_.type + ')'
                            }) -join ''
                        } finally {
                            $reader.Dispose()
                        }
                    } catch {
                        $safeValidation = ''
                    }
                }
                throw "A Jina archive-embedding batch request failed: $safeStatus$safeValidation."
            }
            Start-Sleep -Milliseconds 250
        }
    }

    $vectors = @($response.data | Sort-Object { [int]$_.index })
    if ($vectors.Count -ne $batch.Count) {
        throw 'Jina returned an unexpected archive-embedding count.'
    }
    for ($index = 0; $index -lt $batch.Count; $index++) {
        if ([int]$vectors[$index].index -ne $index) {
            throw 'Jina returned an invalid archive-embedding index.'
        }
        $vector = @($vectors[$index].embedding)
        if ($vector.Count -ne 1024) {
            throw 'Jina returned an invalid archive-embedding dimension.'
        }
        foreach ($value in $vector) {
            $number = [double]$value
            if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) {
                throw 'Jina returned a non-finite archive embedding.'
            }
        }
        $generated.Add([ordered]@{
            id = $batch[$index].id
            contentHash = $batch[$index].contentHash
            vector = $vector
        })
    }
    Write-Output ("Generated archive vectors {0}-{1} of {2}." -f
        ($offset + 1), ($last + 1), $prepared.Count)
}

$resource = [ordered]@{
    schemaVersion = 1
    model = 'jina-embeddings-v3'
    task = 'retrieval.passage'
    dimensions = 1024
    normalized = $true
    archiveCount = $prepared.Count
    entries = $generated
}
$json = $resource | ConvertTo-Json -Depth 8 -Compress
[IO.File]::WriteAllText($OutputPath, $json, [Text.UTF8Encoding]::new($false))
Write-Output ("Saved {0} archive embeddings." -f $generated.Count)
