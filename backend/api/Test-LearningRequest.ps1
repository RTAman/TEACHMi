#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'Invoke-LearningRequest.ps1'
$spec = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'openapi.json') -Raw | ConvertFrom-Json -AsHashtable -Depth 100
$testSessionId = [guid]'00000000-0000-0000-0000-000000000001'
$testState = @{ Captured = $null; Status = 200; Content = '{"version":7}' }

# Shadows only this test process's command. No network request is made.
$fakeRequest = {
    param($Method, $Uri, $Headers, $TimeoutSec, $SkipHttpErrorCheck, $MaximumRetryCount,
          $MaximumRedirection, $ContentType, $Body, $WebSession)
    $testState.Captured = $PSBoundParameters
    [pscustomobject]@{
        StatusCode = $testState.Status; Content = $testState.Content
        Headers = @{ 'Location' = '/api/v1/sessions/test'; 'Cache-Control' = 'no-store'; 'Set-Cookie' = 'hidden-cookie' }
    }
}.GetNewClosure()
Set-Item -Path Function:Invoke-WebRequest -Value $fakeRequest
function Assert-Contract([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-Contract $rejected 'Invalid client input was accepted.'
}

$count = 0
foreach ($path in $spec.paths.Keys) {
    foreach ($method in @('get', 'post', 'put')) {
        if (-not $spec.paths[$path].ContainsKey($method)) { continue }
        $operation = $spec.paths[$path][$method]
        $parameters = @{ OperationId = $operation.operationId; SessionId = $testSessionId; IdempotencyKey = 'stable-key' }
        if ($operation.ContainsKey('requestBody')) { $parameters['Body'] = @{ expectedVersion = 7; text = '한글과 "인용"' } }
        $parameters['AuthHeaders'] = @{ 'X-Test-Opaque-Auth' = 'test-credential' }
        $preview = & $runner @parameters -Preview
        Assert-Contract ($preview.Method -ceq $method.ToUpperInvariant()) 'Method differs from OpenAPI.'
        Assert-Contract ($preview.Uri -ceq ('http://localhost:8080' + $path.Replace('{sessionId}', $testSessionId.ToString()))) 'Path differs from OpenAPI.'
        Assert-Contract (($preview | ConvertTo-Json -Depth 10) -notmatch 'test-credential') 'Preview exposed authentication.'
        $testState.Captured = $null
        $result = & $runner @parameters
        Assert-Contract ($testState.Captured.Headers['X-Test-Opaque-Auth'] -ceq 'test-credential') 'Opaque auth was not forwarded.'
        Assert-Contract ($testState.Captured.MaximumRetryCount -eq 0 -and $testState.Captured.MaximumRedirection -eq 0) 'Automatic retries or redirects enabled.'
        Assert-Contract ($result.Body.version -eq 7) 'JSON response was not parsed.'
        Assert-Contract (($result | ConvertTo-Json -Depth 10) -notmatch 'hidden-cookie|test-credential') 'Output exposed authentication metadata.'
        if ($operation.ContainsKey('requestBody')) {
            $sent = $testState.Captured.Body | ConvertFrom-Json -AsHashtable
            Assert-Contract ($sent.expectedVersion -is [long] -and $sent.text -ceq '한글과 "인용"') 'JSON types or text changed.'
            # Only routes that document IdempotencyKey send it (hesitations is recorded once per question instead).
            $declaresKey = $operation.ContainsKey('parameters') -and
                @($operation.parameters | Where-Object { $_.ContainsKey('$ref') -and $_['$ref'] -eq '#/components/parameters/IdempotencyKey' }).Count -gt 0
            if ($declaresKey) {
                Assert-Contract ($testState.Captured.Headers['Idempotency-Key'] -ceq 'stable-key') 'Request key changed.'
            } else {
                Assert-Contract (-not $testState.Captured.Headers.ContainsKey('Idempotency-Key')) 'Undocumented request key sent.'
            }
        }
        $count++
    }
}
Assert-Contract ($count -eq 10) 'Unexpected documented operation count.'
$testState.Status = 204; $testState.Content = ''
$empty = & $runner -OperationId getActiveSession
Assert-Contract ($empty.StatusCode -eq 204 -and $null -eq $empty.Body) '204 was not preserved.'
$testState.Status = 503; $testState.Content = '{"code":"LEARNING_SOURCE_INTEGRATION_PENDING","currentSession":null}'
$pending = & $runner -OperationId getSession -SessionId $testSessionId
Assert-Contract ($pending.StatusCode -eq 503 -and $pending.Body.code -ceq 'LEARNING_SOURCE_INTEGRATION_PENDING') 'Domain error was lost.'
$testState.Status = 400; $testState.Content = 'framework error'
Assert-Contract ((& $runner -OperationId getSession -SessionId $testSessionId).Body -ceq 'framework error') 'Framework body was lost.'
Assert-Rejected { & $runner -OperationId unknown -Preview }
Assert-Rejected { & $runner -OperationId getSession -Preview }
Assert-Rejected { & $runner -OperationId createSession -Body @{} -Preview }
Assert-Rejected { & $runner -OperationId getActiveSession -Body @{} -Preview }
Assert-Rejected { & $runner -OperationId getActiveSession -BaseUri 'https://user:secret@example.invalid' -Preview }
Write-Output 'REQUEST RUNNER CHECKS PASSED: 10 routes, JSON/auth preservation, 204/errors, no retries/redirects, invalid inputs.'
