<#
.SYNOPSIS
    A파트(인증·자료) 배포 검수. 회원 두 명으로 가입부터 자료 등록, 접근 차단, 로그아웃까지 실제 서버에 확인합니다.

.DESCRIPTION
    Windows PowerShell 5.1과 PowerShell 7(Windows·macOS·Linux)에서 실행합니다.
    매 실행마다 새 테스트 계정 두 개를 만들고(비밀번호는 무작위, 출력하지 않음), 테스트용 PDF도 스크립트가 직접 만듭니다.
    학습 세션 생성까지만 확인하며 AI(Gemini)는 호출하지 않습니다. 실패한 항목이 있으면 종료 코드 1을 반환합니다.

.EXAMPLE
    ./api/Test-AuthMaterialsDeployment.ps1 -BaseUri https://teachmi-frontend.vercel.app
    ./api/Test-AuthMaterialsDeployment.ps1 -BaseUri http://localhost:8080
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$BaseUri,
    [string]$EmailDomain = 'example.com',
    [ValidateRange(5, 300)][int]$AnalysisTimeoutSeconds = 60,
    [switch]$SkipLearningSession
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $BaseUri.IsAbsoluteUri -or $BaseUri.Scheme -notin @('http', 'https') -or $BaseUri.UserInfo -or $BaseUri.Query) {
    throw 'BaseUri는 http(s)로 시작하는 서버 주소여야 합니다. 예: https://teachmi-frontend.vercel.app'
}
Add-Type -AssemblyName System.Net.Http
$root = $BaseUri.AbsoluteUri.TrimEnd('/')
$api = "$root/api/v1"
$results = New-Object System.Collections.Generic.List[object]

# ---------------------------------------------------------------- 기록

function Add-Check([string]$Name, [bool]$Passed, [string]$Detail = '') {
    $results.Add([pscustomobject]@{ Result = $(if ($Passed) { 'PASS' } else { 'FAIL' }); Check = $Name; Detail = $Detail })
    $color = if ($Passed) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1} {2}" -f $(if ($Passed) { 'PASS' } else { 'FAIL' }), $Name, $Detail) -ForegroundColor $color
}

function Get-Prop($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

# ---------------------------------------------------------------- HTTP

function New-Client([bool]$UseCookies = $true) {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseCookies = $UseCookies
    $handler.AllowAutoRedirect = $false
    if ($UseCookies) { $handler.CookieContainer = New-Object System.Net.CookieContainer }
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    return [pscustomobject]@{ Client = $client; Handler = $handler; Csrf = $null; Id = $null; Email = $null }
}

function Invoke-Api($Member, [string]$Method, [string]$Path, $Json = $null, [hashtable]$Headers = @{},
                    [System.Net.Http.HttpContent]$Content = $null, [switch]$NoCsrf) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($Method), "$api$Path")
    $request.Headers.Accept.ParseAdd('application/json')
    if ($Method -ne 'GET' -and $Member.Csrf -and -not $NoCsrf) {
        [void]$request.Headers.TryAddWithoutValidation('X-CSRF-Token', $Member.Csrf)
    }
    foreach ($key in $Headers.Keys) { [void]$request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key]) }
    if ($null -ne $Content) {
        $request.Content = $Content
    } elseif ($null -ne $Json) {
        $body = $Json | ConvertTo-Json -Depth 10 -Compress
        $request.Content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
    }
    $response = $Member.Client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $parsed = $null
    if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
    $setCookie = @()
    $values = $null
    if ($response.Headers.TryGetValues('Set-Cookie', [ref]$values)) { $setCookie = @($values) }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed; Text = $text; SetCookie = $setCookie }
}

function Get-SessionCookie($Member) {
    foreach ($cookie in $Member.Handler.CookieContainer.GetCookies([uri]$root)) {
        if ($cookie.Name -eq 'TEACHMI_SESSION') { return $cookie.Value }
    }
    return $null
}

# ---------------------------------------------------------------- 테스트 PDF (영문, 표준 Helvetica)

function New-TestPdf([object[]]$Pages) {
    $objects = New-Object System.Collections.Generic.List[string]
    $kids = @()
    $pageObjects = @()
    $next = 4
    foreach ($lines in $Pages) {
        $pageObjects += ,@($next, ($next + 1), $lines)
        $kids += "$next 0 R"
        $next += 2
    }
    $objects.Add('<< /Type /Catalog /Pages 2 0 R >>')
    $objects.Add("<< /Type /Pages /Kids [$($kids -join ' ')] /Count $($Pages.Count) >>")
    $objects.Add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    foreach ($page in $pageObjects) {
        $stream = 'BT /F1 14 Tf 18 TL 50 700 Td'
        $first = $true
        foreach ($line in @($page[2])) {
            if (-not $line) { continue }
            $escaped = $line.Replace('\', '\\').Replace('(', '\(').Replace(')', '\)')
            $stream += $(if ($first) { " ($escaped) Tj" } else { " T* ($escaped) Tj" })
            $first = $false
        }
        $stream += ' ET'
        $objects.Add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents $($page[1]) 0 R >>")
        $objects.Add("<< /Length $($stream.Length) >>`nstream`n$stream`nendstream")
    }
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append("%PDF-1.4`n")
    $offsets = @()
    for ($i = 0; $i -lt $objects.Count; $i++) {
        $offsets += $builder.Length
        [void]$builder.Append("$($i + 1) 0 obj`n$($objects[$i])`nendobj`n")
    }
    $xref = $builder.Length
    [void]$builder.Append("xref`n0 $($objects.Count + 1)`n0000000000 65535 f `n")
    foreach ($offset in $offsets) { [void]$builder.Append(("{0:D10} 00000 n `n" -f $offset)) }
    [void]$builder.Append("trailer`n<< /Size $($objects.Count + 1) /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n")
    return [System.Text.Encoding]::ASCII.GetBytes($builder.ToString())
}

function Send-File($Member, [string]$Key, [string]$FileName, [byte[]]$Bytes) {
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $file = New-Object System.Net.Http.ByteArrayContent(, $Bytes)
    $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/pdf')
    $form.Add($file, 'file', $FileName)
    return Invoke-Api $Member 'POST' '/materials' -Headers @{ 'Idempotency-Key' = $Key } -Content $form
}

function Wait-Analysis($Member, [string]$MaterialId) {
    $deadline = (Get-Date).AddSeconds($AnalysisTimeoutSeconds)
    do {
        $current = Invoke-Api $Member 'GET' "/materials/$MaterialId"
        if ($current.Status -ne 200 -or (Get-Prop $current.Body 'status') -ne 'analyzing') { return $current }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $current
}

function New-Key { [guid]::NewGuid().ToString() }

# ---------------------------------------------------------------- 실행

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$password = 'Qa-' + [guid]::NewGuid().ToString('N').Substring(0, 20)
$a = New-Client; $a.Email = "qa-a-$stamp@$EmailDomain"
$b = New-Client; $b.Email = "qa-b-$stamp@$EmailDomain"
Write-Host "대상: $root"
Write-Host "테스트 계정: $($a.Email), $($b.Email) (비밀번호는 무작위로 만들고 출력하지 않습니다)"

try {
    # 프론트 rewrite를 거쳐도 닿는 /api 경로로 확인합니다. 로그인 전이므로 401이 정상입니다.
    $health = $a.Client.GetAsync("$api/auth/me").GetAwaiter().GetResult()
    Add-Check '서버 연결 (/api 경로)' ([int]$health.StatusCode -eq 401) "HTTP $([int]$health.StatusCode)"
    if ([int]$health.StatusCode -ne 401) { exit 1 }
} catch {
    Add-Check '서버 상태' $false '서버에 연결할 수 없습니다. 주소와 배포 상태를 확인하세요.'
    exit 1
}

# 1. 가입·중복 가입
foreach ($m in @($a, $b)) {
    $signup = Invoke-Api $m 'POST' '/auth/signup' @{ email = $m.Email; password = $password }
    Add-Check "가입 $($m.Email)" ($signup.Status -eq 201) "HTTP $($signup.Status)"
}
$dup = Invoke-Api $a 'POST' '/auth/signup' @{ email = $a.Email.ToUpperInvariant(); password = $password }
Add-Check '같은 이메일(대소문자 달라도) 중복 가입 차단' ($dup.Status -eq 409) "HTTP $($dup.Status)"

# 2. 로그인 실패는 같은 안내
$wrong = Invoke-Api $a 'POST' '/auth/login' @{ email = $a.Email; password = 'wrong-password-123' }
$unknown = Invoke-Api $a 'POST' '/auth/login' @{ email = "qa-none-$stamp@$EmailDomain"; password = 'wrong-password-123' }
Add-Check '틀린 비밀번호·없는 이메일 → 같은 401' ($wrong.Status -eq 401 -and $unknown.Status -eq 401 -and $wrong.Text -eq $unknown.Text) "HTTP $($wrong.Status)/$($unknown.Status)"

# 3. 로그인·쿠키 속성
foreach ($m in @($a, $b)) {
    $login = Invoke-Api $m 'POST' '/auth/login' @{ email = $m.Email; password = $password }
    $m.Csrf = Get-Prop $login.Body 'csrfToken'
    $m.Id = Get-Prop $login.Body 'id'
    $cookie = ($login.SetCookie | Where-Object { $_ -like 'TEACHMI_SESSION=*' } | Select-Object -First 1)
    $flagsOk = $cookie -and $cookie -match 'HttpOnly' -and ($BaseUri.Scheme -ne 'https' -or $cookie -match 'Secure')
    Add-Check "로그인 $($m.Email)" ($login.Status -eq 200 -and $m.Csrf -and $flagsOk) "HTTP $($login.Status), HttpOnly$(if ($BaseUri.Scheme -eq 'https') { '+Secure' }) 쿠키 $(if ($flagsOk) { '확인' } else { '없음' })"
}
$me = Invoke-Api $a 'GET' '/auth/me'
Add-Check '로그인 확인 /auth/me' ($me.Status -eq 200 -and (Get-Prop $me.Body 'id') -eq $a.Id) "HTTP $($me.Status)"
$anonymous = Invoke-Api (New-Client) 'GET' '/auth/me'
Add-Check '비로그인 /auth/me → 401' ($anonymous.Status -eq 401) "HTTP $($anonymous.Status)"

# 4. 직접 입력 자료
$text = Invoke-Api $a 'POST' '/materials' @{ text = 'Depreciation allocates the cost of an asset over its useful life.'; concept = @{ name = 'Depreciation'; scope = 'Why cost is spread over the useful life' } } -Headers @{ 'Idempotency-Key' = (New-Key) }
$textId = Get-Prop $text.Body 'materialId'
Add-Check '직접 입력 자료 등록' ($text.Status -eq 201 -and (Get-Prop $text.Body 'status') -eq 'done') "HTTP $($text.Status)"
$noCsrf = Invoke-Api $a 'POST' '/materials' @{ text = 'csrf check' } -Headers @{ 'Idempotency-Key' = (New-Key) } -NoCsrf
Add-Check 'CSRF 헤더 없는 변경 요청 → 403' ($noCsrf.Status -eq 403) "HTTP $($noCsrf.Status)"

# 5. 파일 업로드·분석
$pdf = New-TestPdf @(
    @('Depreciation', 'Depreciation allocates the cost of an asset over its useful life.', 'It matches cost with revenue.'),
    @('Useful Life', 'The useful life is the period an asset is expected to be used.'))
$upload = Send-File $a (New-Key) 'qa-lecture.pdf' $pdf
$fileId = Get-Prop $upload.Body 'materialId'
Add-Check 'PDF 업로드 접수' ($upload.Status -eq 202 -and (Get-Prop $upload.Body 'status') -eq 'analyzing') "HTTP $($upload.Status)"
$fileConceptId = $null
if ($fileId) {
    $analyzed = Wait-Analysis $a $fileId
    $concepts = @(Get-Prop $analyzed.Body 'concepts')
    $fileConceptId = if ($concepts.Count -gt 0) { Get-Prop $concepts[0] 'conceptId' } else { $null }
    Add-Check 'PDF 분석 완료·개념 후보' ((Get-Prop $analyzed.Body 'status') -eq 'done' -and $concepts.Count -ge 2) "status=$(Get-Prop $analyzed.Body 'status'), 개념 $($concepts.Count)개"
}
$unsupported = Invoke-Api $a 'POST' '/materials' -Headers @{ 'Idempotency-Key' = (New-Key) } -Content $(
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $form.Add((New-Object System.Net.Http.ByteArrayContent(, [System.Text.Encoding]::UTF8.GetBytes('hello'))), 'file', 'notes.txt')
    $form)
Add-Check '지원하지 않는 형식 → 415' ($unsupported.Status -eq 415) "HTTP $($unsupported.Status)"

# 6. 글자 없는 PDF → no_text → 재시도
$blank = Send-File $a (New-Key) 'qa-scan.pdf' (New-TestPdf @(, @()))
$blankId = Get-Prop $blank.Body 'materialId'
if ($blankId) {
    $failed = Wait-Analysis $a $blankId
    Add-Check '글자 없는 PDF → failed/no_text' ((Get-Prop $failed.Body 'status') -eq 'failed' -and (Get-Prop $failed.Body 'errorCode') -eq 'no_text') "status=$(Get-Prop $failed.Body 'status'), errorCode=$(Get-Prop $failed.Body 'errorCode')"
    $retry = Invoke-Api $a 'POST' "/materials/$blankId/retry" -Headers @{ 'Idempotency-Key' = (New-Key) }
    Add-Check '실패 자료 다시 시도 → 202' ($retry.Status -eq 202 -and (Get-Prop $retry.Body 'analysisVersion') -eq 2) "HTTP $($retry.Status)"
    [void](Wait-Analysis $a $blankId)
} else {
    Add-Check '글자 없는 PDF 업로드' $false "HTTP $($blank.Status)"
}

# 7. 다른 회원 접근 차단
foreach ($id in @($textId, $fileId)) {
    if (-not $id) { continue }
    $peek = Invoke-Api $b 'GET' "/materials/$id"
    Add-Check '다른 회원 자료 조회 → 404' ($peek.Status -eq 404) "HTTP $($peek.Status)"
}
if ($fileId -and $fileConceptId) {
    $edit = Invoke-Api $b 'PATCH' "/materials/$fileId/concepts/$fileConceptId" @{ name = 'hijack' }
    Add-Check '다른 회원 개념 수정 → 404' ($edit.Status -eq 404) "HTTP $($edit.Status)"
}

# 8. B 학습 세션 연결 (AI 호출 없음)
if (-not $SkipLearningSession -and $fileId -and $fileConceptId) {
    $session = Invoke-Api $a 'POST' '/sessions' @{ materialId = $fileId; conceptId = $fileConceptId } -Headers @{ 'Idempotency-Key' = (New-Key) }
    Add-Check '업로드 자료 개념으로 학습 시작(B)' ($session.Status -eq 201) "HTTP $($session.Status)"
    $stolen = Invoke-Api $b 'POST' '/sessions' @{ materialId = $fileId; conceptId = $fileConceptId } -Headers @{ 'Idempotency-Key' = (New-Key) }
    Add-Check '다른 회원 자료로 학습 시작 → 404' ($stolen.Status -eq 404) "HTTP $($stolen.Status)"
}

# 9. 로그아웃 → 이전 쿠키 차단 → 재로그인 복원
$oldCookie = Get-SessionCookie $a
$logout = Invoke-Api $a 'POST' '/auth/logout'
Add-Check '로그아웃 → 204' ($logout.Status -eq 204) "HTTP $($logout.Status)"
if ($oldCookie) {
    $replay = New-Client $false
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$api/auth/me")
    [void]$request.Headers.TryAddWithoutValidation('Cookie', "TEACHMI_SESSION=$oldCookie")
    $reused = $replay.Client.SendAsync($request).GetAwaiter().GetResult()
    Add-Check '로그아웃한 쿠키 재사용 → 401' ([int]$reused.StatusCode -eq 401) "HTTP $([int]$reused.StatusCode)"
}
$again = New-Client
$relogin = Invoke-Api $again 'POST' '/auth/login' @{ email = $a.Email; password = $password }
$again.Csrf = Get-Prop $relogin.Body 'csrfToken'
if ($textId) {
    $restored = Invoke-Api $again 'GET' "/materials/$textId"
    Add-Check '재로그인 후 내 자료 복원' ($relogin.Status -eq 200 -and $restored.Status -eq 200) "HTTP $($relogin.Status)/$($restored.Status)"
}
[void](Invoke-Api $again 'POST' '/auth/logout')
[void](Invoke-Api $b 'POST' '/auth/logout')

# ---------------------------------------------------------------- 결과

$failedChecks = @($results | Where-Object { $_.Result -eq 'FAIL' })
Write-Host ''
Write-Host ("결과: {0}개 중 {1}개 통과, {2}개 실패" -f $results.Count, ($results.Count - $failedChecks.Count), $failedChecks.Count) -ForegroundColor $(if ($failedChecks.Count) { 'Red' } else { 'Green' })
Write-Host "검수에 쓴 계정($($a.Email), $($b.Email))과 자료는 서버에 남아 있습니다."
if ($failedChecks.Count) { exit 1 }
exit 0
