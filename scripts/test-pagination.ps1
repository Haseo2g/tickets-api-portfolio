param(
    [string]$BaseUrl = 'https://k9okaxkcpg.execute-api.us-east-2.amazonaws.com',
    [ValidateRange(3, 100)]
    [int]$MaxPages = 20
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$limit = 2
$runId = 'pagination-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$createdIds = [System.Collections.Generic.List[string]]::new()
$seenIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$seenTokens = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$pageSizes = [System.Collections.Generic.List[int]]::new()
$pageCount = 0

function Invoke-TicketsRequest {
    param(
        [string]$Method,
        [string]$Uri,
        [int]$ExpectedStatus,
        [object]$Body
    )

    $request = @{
        Uri = $Uri
        Method = $Method
        TimeoutSec = 20
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
    }

    try {
        $response = Invoke-WebRequest @request
    } catch {
        $httpStatus = if ($null -ne $_.Exception.Response) {
            [int]$_.Exception.Response.StatusCode
        } else {
            'sem resposta HTTP'
        }
        throw "$Method $Uri falhou (HTTP $httpStatus): $($_.Exception.Message)"
    }

    if ([int]$response.StatusCode -ne $ExpectedStatus) {
        throw "$Method $Uri retornou HTTP $($response.StatusCode); esperado $ExpectedStatus"
    }
    try {
        return $response.Content | ConvertFrom-Json
    } catch {
        throw "$Method $Uri retornou JSON inválido: $($_.Exception.Message)"
    }
}

try {
    Write-Host "Execução: $runId"
    Write-Host "Criando 5 chamados fictícios (POST /tickets; HTTP 201 esperado)..."
    for ($number = 1; $number -le 5; $number++) {
        $body = @{
            title = "$runId-$number"
            description = 'Chamado fictício para validação de paginação do portfólio.'
        }
        $created = Invoke-TicketsRequest -Method POST -Uri "$base/tickets" -ExpectedStatus 201 -Body $body
        if ([string]::IsNullOrWhiteSpace([string]$created.id) -or $created.title -ne $body.title -or $created.status -ne 'OPEN') {
            throw "POST #$number retornou dados inesperados."
        }
        if ($createdIds.Contains([string]$created.id)) {
            throw "POST #$number retornou ID duplicado: $($created.id)"
        }
        $createdIds.Add([string]$created.id)
    }

    Write-Host "Percorrendo GET /tickets?limit=$limit (HTTP 200 esperado)..."
    $nextToken = $null
    do {
        if ($pageCount -ge $MaxPages) {
            throw "Limite de $MaxPages páginas atingido antes do fim da paginação."
        }
        $uri = "$base/tickets?limit=$limit"
        if (-not [string]::IsNullOrEmpty($nextToken)) {
            if (-not $seenTokens.Add($nextToken)) {
                throw 'nextToken repetido; navegação interrompida para evitar loop.'
            }
            $uri += '&nextToken=' + [Uri]::EscapeDataString($nextToken)
        }

        $page = Invoke-TicketsRequest -Method GET -Uri $uri -ExpectedStatus 200
        if ($null -eq $page.items) {
            throw 'Resposta de listagem sem o campo items.'
        }
        $items = @($page.items)
        $pageCount++
        $pageSizes.Add($items.Count)
        if ($items.Count -gt $limit) {
            throw "Página #$pageCount retornou $($items.Count) itens; limite $limit."
        }
        foreach ($item in $items) {
            if ([string]::IsNullOrWhiteSpace([string]$item.id)) {
                throw "Página #$pageCount contém item sem ID."
            }
            if (-not $seenIds.Add([string]$item.id)) {
                throw "ID duplicado entre páginas: $($item.id)"
            }
        }
        $nextToken = if ([string]::IsNullOrEmpty([string]$page.nextToken)) { $null } else { [string]$page.nextToken }
    } while ($null -ne $nextToken)

    $foundCreated = @($createdIds | Where-Object { $seenIds.Contains($_) })
    if ($foundCreated.Count -ne 5) {
        $missing = @($createdIds | Where-Object { -not $seenIds.Contains($_) })
        throw "Apenas $($foundCreated.Count) dos 5 chamados foram encontrados. IDs ausentes: $($missing -join ', ')"
    }
    if ($pageCount -lt 3) {
        throw "Foram percorridas apenas $pageCount páginas; 5 chamados com limit=2 exigem ao menos 3."
    }

    [pscustomobject]@{
        result = 'PASS'
        runId = $runId
        createdCount = $createdIds.Count
        pageCount = $pageCount
        limit = $limit
        pageSizes = @($pageSizes)
        foundCreatedCount = $foundCreated.Count
        createdIds = @($createdIds)
        foundCreatedIds = $foundCreated
        seenIds = @($seenIds)
        checks = @('HTTP 201/200', '5 IDs encontrados', 'máximo de 2 itens por página', 'IDs sem duplicação', 'nextToken termina sem repetição')
    } | ConvertTo-Json -Depth 5
} catch {
    [Console]::Error.WriteLine("FALHA na execução $runId após $($createdIds.Count) criações e $pageCount páginas: $($_.Exception.Message)")
    if ($createdIds.Count -gt 0) {
        [Console]::Error.WriteLine("IDs criados nesta execução: $($createdIds -join ', ')")
    }
    exit 1
}
