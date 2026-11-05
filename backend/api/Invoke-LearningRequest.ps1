#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OperationId,
    [uri]$BaseUri = 'http://localhost:8080',
    [Nullable[guid]]$SessionId,
    [string]$IdempotencyKey,
    [hashtable]$Body,
    [hashtable]$AuthHeaders = @{},
    [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession,
    [ValidateRange(1, 120)][int]$TimeoutSeconds = 30,
    [switch]$Preview
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $BaseUri.IsAbsoluteUri -or $BaseUri.Scheme -notin @('http', 'https') -or
    $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment) {
    throw 'BaseUri must be an absolute HTTP(S) URL without credentials, query or fragment.'
}
$spec = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'openapi.json') -Raw | ConvertFrom-Json -AsHashtable -Depth 100
$matchedOperations = @(
    foreach ($path in $spec.paths.Keys) {
        foreach ($verb in @('get', 'post', 'put', 'patch', 'delete', 'head', 'options')) {
            if ($spec.paths[$path].ContainsKey($verb) -and $spec.paths[$path][$verb].operationId -ceq $OperationId) {
                @{ Path = $path; Method = $verb.ToUpperInvariant(); Definition = $spec.paths[$path][$verb] }
            }
        }
    }
)
if ($matchedOperations.Count -ne 1) { throw "Unknown or ambiguous OpenAPI operationId: $OperationId" }
$operation = $matchedOperations[0]
$requestPath = $operation.Path
if ($requestPath.Contains('{sessionId}')) {
    if ($null -eq $SessionId -or $SessionId -eq [guid]::Empty) { throw 'This operation requires SessionId.' }
    $requestPath = $requestPath.Replace('{sessionId}', $SessionId.ToString())
}
$headers = $AuthHeaders.Clone()
$headers['Accept'] = 'application/json'
$needsKey = $operation.Definition.ContainsKey('parameters') -and
    @($operation.Definition.parameters | Where-Object { $_.ContainsKey('$ref') -and $_['$ref'] -eq '#/components/parameters/IdempotencyKey' }).Count -gt 0
if ($needsKey) {
    if ([string]::IsNullOrWhiteSpace($IdempotencyKey) -or $IdempotencyKey.Length -gt 128 -or $IdempotencyKey.Contains([char]0)) {
        throw 'Supply a 1-128 character IdempotencyKey; keep it unchanged when retrying the same action.'
    }
    $headers['Idempotency-Key'] = $IdempotencyKey
}
$needsBody = $operation.Definition.ContainsKey('requestBody')
if ($needsBody -and $null -eq $Body) { throw 'This operation requires a Body hashtable.' }
if (-not $needsBody -and $null -ne $Body) { throw 'This read operation does not accept Body.' }
$payload = if ($needsBody) { ConvertTo-Json -InputObject $Body -Depth 40 -Compress } else { $null }
$uri = $BaseUri.AbsoluteUri.TrimEnd('/') + $requestPath
if ($Preview) {
    # Never display authentication headers or cookies, even in a preview.
    return [pscustomobject]@{ Method = $operation.Method; Uri = $uri; Body = $payload }
}
$parameters = @{
    Method = $operation.Method; Uri = $uri; Headers = $headers; TimeoutSec = $TimeoutSeconds
    SkipHttpErrorCheck = $true; MaximumRetryCount = 0; MaximumRedirection = 0
}
if ($needsBody) { $parameters['ContentType'] = 'application/json; charset=utf-8'; $parameters['Body'] = $payload }
if ($null -ne $WebSession) { $parameters['WebSession'] = $WebSession }
$response = Invoke-WebRequest @parameters
$content = [string]$response.Content
$parsed = $null
if (-not [string]::IsNullOrWhiteSpace($content)) {
    try { $parsed = ConvertFrom-Json -InputObject $content -AsHashtable -Depth 100 }
    catch { $parsed = $content } # Framework errors need not use B's ErrorBody.
}
# Include only the response metadata needed by B clients, not Set-Cookie or authentication data.
[pscustomobject]@{
    StatusCode = [int]$response.StatusCode
    Location = ($response.Headers['Location'] -join ', ')
    CacheControl = ($response.Headers['Cache-Control'] -join ', ')
    Body = $parsed
}
