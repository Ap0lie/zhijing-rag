param(
    [ValidateSet("Start", "Stop", "Status")]
    [string]$Action = "Status"
)

$ErrorActionPreference = "Stop"
$project = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $project

if (-not $env:TEMP) {
    throw "TEMP is not configured"
}

$gpuServices = @("embedding-model", "reranker-model", "local-llm")
$workerServices = @("indexer-worker", "graph-worker", "global-graph-worker")
$runtimeServices = @("backend", "parser-worker")
$expectedImageId = "sha256:34dba06e0bf0f4530c38d06adb477b2b3ae8f0c9a43a4f32660a0e05bb63410c"
$expectedManifest = "16981dc38075623ddec4fdcf7f055c89688f44a92076f534919f465be46c82e7"
$hasher = [Security.Cryptography.SHA256]::Create()
try {
    $projectHash = [Convert]::ToHexString(
        $hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($project.ToLowerInvariant()))
    ).Substring(0, 16).ToLowerInvariant()
} finally {
    $hasher.Dispose()
}
$stateFile = Join-Path $env:TEMP "rag-mineru-$projectHash.json"

function Invoke-Compose {
    param(
        [string[]]$Arguments,
        [string]$FailureMessage
    )
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
}

function Get-RunningServices {
    $services = @(& docker compose ps --services --status running)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read the Compose service state"
    }
    return @($services | Where-Object { $_ })
}

function Get-Conflicts {
    param([string[]]$Running)
    return @($Running | Where-Object { $_ -in $gpuServices })
}

function Get-ServiceEnvironment {
    param(
        [string]$Service,
        [string]$Name,
        [string]$Fallback
    )
    $containers = @(& docker compose ps -q $Service | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0 -or $containers.Count -eq 0) {
        return $Fallback
    }
    $prefix = "$Name="
    $line = @(
        & docker inspect --format "{{range .Config.Env}}{{println .}}{{end}}" $containers[0]
    ) | Where-Object { $_.StartsWith($prefix) } | Select-Object -First 1
    if (-not $line) {
        return $Fallback
    }
    return $line.Substring($prefix.Length)
}

function Invoke-WithRuntime {
    param(
        [string]$MineruEnabled,
        [string]$GpuProfile,
        [string]$EmbeddingEnabled,
        [string]$RerankEnabled,
        [string[]]$Services = $runtimeServices
    )
    if ($Services.Count -eq 0) {
        return
    }
    $values = @{
        MINERU_ENABLED = $MineruEnabled
        GPU_ACTIVE_PROFILE = $GpuProfile
        EMBEDDING_ENABLED = $EmbeddingEnabled
        RERANK_ENABLED = $RerankEnabled
    }
    $previous = @{}
    try {
        foreach ($name in $values.Keys) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
            [Environment]::SetEnvironmentVariable($name, $values[$name], "Process")
        }
        Invoke-Compose `
            -Arguments (@("up", "-d", "--no-deps", "--force-recreate") + $Services) `
            -FailureMessage "Could not recreate the requested runtime services"
    } finally {
        foreach ($name in $values.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process")
        }
    }
}

function Read-State {
    if (-not (Test-Path -LiteralPath $stateFile)) {
        return $null
    }
    try {
        $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    } catch {
        throw "MINERU_STATE_INVALID: remove $stateFile after checking the running services"
    }
    if ($state.project -ne $project) {
        throw "MINERU_STATE_INVALID: project identity does not match"
    }
    return $state
}

function Restore-Workers {
    param([string[]]$Services)
    if ($Services.Count -gt 0) {
        Invoke-Compose `
            -Arguments (@("--profile", "graph", "start") + $Services) `
            -FailureMessage "Could not restore the previously running workers"
    }
}

if ($Action -eq "Status") {
    Invoke-Compose `
        -Arguments @(
            "ps", "mineru", "parser-worker", "indexer-worker", "graph-worker",
            "global-graph-worker", "embedding-model", "reranker-model"
        ) `
        -FailureMessage "Could not read the MinerU profile status"

    $running = Get-RunningServices
    $conflicts = @(Get-Conflicts -Running $running)
    $mineruRunning = "mineru" -in $running
    $parserEnabled = (Get-ServiceEnvironment "parser-worker" "MINERU_ENABLED" "false") -eq "true"
    $gpuProfile = Get-ServiceEnvironment "parser-worker" "GPU_ACTIVE_PROFILE" "none"
    $backendEmbedding = (Get-ServiceEnvironment "backend" "EMBEDDING_ENABLED" "false") -eq "true"
    $backendRerank = (Get-ServiceEnvironment "backend" "RERANK_ENABLED" "false") -eq "true"
    $stateExists = Test-Path -LiteralPath $stateFile
    $parserActive = $parserEnabled -and $gpuProfile -eq "mineru"

    if ($mineruRunning -and $conflicts.Count -gt 0) {
        throw "GPU_PROFILE_CONFLICT: MinerU and $($conflicts -join ', ') are running together"
    }
    if (
        $mineruRunning -ne $parserActive -or
        $stateExists -ne $parserActive -or
        $parserEnabled -ne ($gpuProfile -eq "mineru") -or
        ($parserActive -and ($backendEmbedding -or $backendRerank))
    ) {
        throw "MINERU_STATE_DRIFT: runtime, parser and persisted profile state do not agree"
    }
    return
}

if ($Action -eq "Start") {
    if (Test-Path -LiteralPath $stateFile) {
        throw "MINERU_ALREADY_ACTIVE: run Status or Stop before starting again"
    }
    $running = Get-RunningServices
    $conflicts = @(Get-Conflicts -Running $running)
    if ($conflicts.Count -gt 0) {
        throw "GPU_PROFILE_CONFLICT: stop these GPU services first: $($conflicts -join ', ')"
    }

    $imageMetadata = & docker image inspect rag-platform-mineru:3.4.4 `
        --format '{{.Id}}|{{index .Config.Labels "io.rag.mineru.model-manifest-sha256"}}'
    $imageId, $manifest = $imageMetadata -split '\|', 2
    if (
        $LASTEXITCODE -ne 0 -or
        $imageId -ne $expectedImageId -or
        $manifest -ne $expectedManifest
    ) {
        throw "MINERU_IMAGE_MISMATCH: rebuild the verified MinerU image"
    }

    $state = [ordered]@{
        project = $project
        mineruWasRunning = "mineru" -in $running
        previouslyRunningWorkers = @($running | Where-Object { $_ -in $workerServices })
        previouslyRunningRuntime = @($running | Where-Object { $_ -in $runtimeServices })
        previousRuntime = [ordered]@{
            mineruEnabled = Get-ServiceEnvironment "parser-worker" "MINERU_ENABLED" "false"
            gpuProfile = Get-ServiceEnvironment "parser-worker" "GPU_ACTIVE_PROFILE" "none"
            embeddingEnabled = Get-ServiceEnvironment "backend" "EMBEDDING_ENABLED" "false"
            rerankEnabled = Get-ServiceEnvironment "backend" "RERANK_ENABLED" "false"
        }
    }
    $state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stateFile -Encoding utf8

    try {
        $workers = @($state.previouslyRunningWorkers)
        if ($workers.Count -gt 0) {
            Invoke-Compose `
                -Arguments (@("--profile", "graph", "stop") + $workers) `
                -FailureMessage "Could not pause index and graph workers"
        }
        Invoke-Compose `
            -Arguments @("--profile", "mineru", "up", "-d", "mineru") `
            -FailureMessage "MinerU failed to start"

        $container = @(& docker compose --profile mineru ps -q mineru | Where-Object { $_ }) |
            Select-Object -First 1
        $containerImage = if ($container) {
            & docker inspect --format "{{.Image}}" $container
        } else {
            ""
        }
        if ($LASTEXITCODE -ne 0 -or $containerImage -ne $imageId) {
            throw "MINERU_IMAGE_MISMATCH: running container does not use the verified image"
        }
        $deadline = (Get-Date).AddMinutes(10)
        $health = "missing"
        do {
            if ($container) {
                $health = & docker inspect --format "{{.State.Health.Status}}" $container
            }
            if ($health -eq "healthy") {
                break
            }
            Start-Sleep -Seconds 5
        } while ((Get-Date) -lt $deadline)
        if ($health -ne "healthy") {
            throw "MinerU did not become healthy within 10 minutes"
        }

        $conflicts = @(Get-Conflicts -Running (Get-RunningServices))
        if ($conflicts.Count -gt 0) {
            throw "GPU_PROFILE_CONFLICT: a conflicting service started while MinerU was warming up"
        }
        Invoke-WithRuntime "true" "mineru" "false" "false"
        $conflicts = @(Get-Conflicts -Running (Get-RunningServices))
        if ($conflicts.Count -gt 0) {
            throw "GPU_PROFILE_CONFLICT: a conflicting service started during activation"
        }
    } catch {
        $startFailure = $_
        try {
            if (-not $state.mineruWasRunning) {
                Invoke-Compose `
                    -Arguments @("--profile", "mineru", "stop", "mineru") `
                    -FailureMessage "Could not stop MinerU during rollback"
            }
            $previousServices = @($state.previouslyRunningRuntime)
            Invoke-WithRuntime `
                $state.previousRuntime.mineruEnabled `
                $state.previousRuntime.gpuProfile `
                $state.previousRuntime.embeddingEnabled `
                $state.previousRuntime.rerankEnabled `
                $previousServices
            $newRuntime = @($runtimeServices | Where-Object { $_ -notin $previousServices })
            if ($newRuntime.Count -gt 0) {
                Invoke-Compose `
                    -Arguments (@("stop") + $newRuntime) `
                    -FailureMessage "Could not stop runtime services created during rollback"
            }
            Restore-Workers -Services @($state.previouslyRunningWorkers)
            Remove-Item -LiteralPath $stateFile -Force
        } catch {
            throw "MinerU start failed: $startFailure; rollback also failed: $_"
        }
        throw $startFailure
    }
    return
}

$state = Read-State
$parserEnabled = (Get-ServiceEnvironment "parser-worker" "MINERU_ENABLED" "false") -eq "true"
$gpuProfile = Get-ServiceEnvironment "parser-worker" "GPU_ACTIVE_PROFILE" "none"
Invoke-Compose `
    -Arguments @("--profile", "mineru", "stop", "mineru") `
    -FailureMessage "Could not stop MinerU"
if ($state) {
    $previousServices = @($state.previouslyRunningRuntime)
    Invoke-WithRuntime `
        $state.previousRuntime.mineruEnabled `
        $state.previousRuntime.gpuProfile `
        $state.previousRuntime.embeddingEnabled `
        $state.previousRuntime.rerankEnabled `
        $previousServices
    $newRuntime = @($runtimeServices | Where-Object { $_ -notin $previousServices })
    if ($newRuntime.Count -gt 0) {
        Invoke-Compose `
            -Arguments (@("stop") + $newRuntime) `
            -FailureMessage "Could not stop runtime services created by the MinerU profile"
    }
    Restore-Workers -Services @($state.previouslyRunningWorkers)
    Remove-Item -LiteralPath $stateFile -Force
} elseif ($parserEnabled -or $gpuProfile -eq "mineru") {
    Invoke-WithRuntime "false" "none" "false" "false"
}
