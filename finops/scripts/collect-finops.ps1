param(
  [string]$Namespace = "circleguard-dev",
  [string]$OutputDir,
  [string]$CostModelPath,
  [switch]$ApplyPolicies
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..")
if (-not $OutputDir) {
  $OutputDir = Join-Path $repoRoot "finops\results"
}
if (-not $CostModelPath) {
  $CostModelPath = Join-Path $repoRoot "finops\cost-model.json"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$policyManifest = Join-Path $repoRoot "finops\manifests\dev-resource-policy.yaml"
$scaleManifest = Join-Path $repoRoot "finops\manifests\dev-scale-to-zero-cronjobs.yaml"
$costModel = Get-Content $CostModelPath -Raw | ConvertFrom-Json

function Invoke-Kubectl([string[]]$Arguments, [switch]$IgnoreErrors) {
  $processInfo = New-Object System.Diagnostics.ProcessStartInfo
  $processInfo.FileName = "kubectl"
  $processInfo.Arguments = ($Arguments | ForEach-Object {
    if ($_ -match "\s") { '"' + ($_.Replace('"', '\"')) + '"' } else { $_ }
  }) -join " "
  $processInfo.RedirectStandardOutput = $true
  $processInfo.RedirectStandardError = $true
  $processInfo.UseShellExecute = $false
  $processInfo.CreateNoWindow = $true

  $process = New-Object System.Diagnostics.Process
  $process.StartInfo = $processInfo
  [void]$process.Start()
  $stdout = $process.StandardOutput.ReadToEnd()
  $stderr = $process.StandardError.ReadToEnd()
  $process.WaitForExit()
  $exitCode = $process.ExitCode

  $output = $stdout
  if (-not [string]::IsNullOrWhiteSpace($stderr)) {
    $output = "$stdout`n$stderr"
  }

  if ($exitCode -ne 0 -and -not $IgnoreErrors) {
    throw "kubectl $($Arguments -join ' ') failed with exit code ${exitCode}: $output"
  }
  return $output
}

function Convert-CpuToCores([string]$value) {
  if ([string]::IsNullOrWhiteSpace($value)) { return 0.0 }
  if ($value.EndsWith("m")) { return [double]($value.TrimEnd("m")) / 1000.0 }
  return [double]$value
}

function Convert-MemoryToGiB([string]$value) {
  if ([string]::IsNullOrWhiteSpace($value)) { return 0.0 }
  if ($value.EndsWith("Ki")) { return [double]($value.Replace("Ki", "")) / 1048576.0 }
  if ($value.EndsWith("Mi")) { return [double]($value.Replace("Mi", "")) / 1024.0 }
  if ($value.EndsWith("Gi")) { return [double]($value.Replace("Gi", "")) }
  if ($value.EndsWith("Ti")) { return [double]($value.Replace("Ti", "")) * 1024.0 }
  return [double]$value / 1073741824.0
}

function Add-Line([System.Collections.Generic.List[string]]$Lines, [string]$Value = "") {
  $Lines.Add($Value) | Out-Null
}

function Add-Block([System.Collections.Generic.List[string]]$Lines, [string]$Title, [string]$Content) {
  Add-Line $Lines "## $Title"
  Add-Line $Lines '```text'
  if ([string]::IsNullOrWhiteSpace($Content)) {
    Add-Line $Lines "(no output)"
  } else {
    $Content.TrimEnd() -split "`r?`n" | ForEach-Object { Add-Line $Lines $_ }
  }
  Add-Line $Lines '```'
  Add-Line $Lines
}

$deployJsonRaw = Invoke-Kubectl @("get", "deployments", "-n", $Namespace, "-o", "json")
$deployJson = $deployJsonRaw | ConvertFrom-Json

$rows = New-Object System.Collections.Generic.List[object]
$missingRequests = 0
$missingLimits = 0
$totalRequestCpu = 0.0
$totalLimitCpu = 0.0
$totalRequestMem = 0.0
$totalLimitMem = 0.0

foreach ($deployment in $deployJson.items) {
  $replicas = if ($deployment.spec.replicas) { [int]$deployment.spec.replicas } else { 1 }
  foreach ($container in $deployment.spec.template.spec.containers) {
    $reqCpuRaw = $container.resources.requests.cpu
    $reqMemRaw = $container.resources.requests.memory
    $limCpuRaw = $container.resources.limits.cpu
    $limMemRaw = $container.resources.limits.memory

    if (-not $reqCpuRaw -or -not $reqMemRaw) { $missingRequests++ }
    if (-not $limCpuRaw -or -not $limMemRaw) { $missingLimits++ }

    $reqCpu = Convert-CpuToCores $reqCpuRaw
    $reqMem = Convert-MemoryToGiB $reqMemRaw
    $limCpu = Convert-CpuToCores $limCpuRaw
    $limMem = Convert-MemoryToGiB $limMemRaw

    $totalRequestCpu += $reqCpu * $replicas
    $totalRequestMem += $reqMem * $replicas
    $totalLimitCpu += $limCpu * $replicas
    $totalLimitMem += $limMem * $replicas

    $rows.Add([pscustomobject]@{
      Deployment = $deployment.metadata.name
      Replicas = $replicas
      Container = $container.name
      RequestCpu = if ($reqCpuRaw) { $reqCpuRaw } else { "missing" }
      RequestMemory = if ($reqMemRaw) { $reqMemRaw } else { "missing" }
      LimitCpu = if ($limCpuRaw) { $limCpuRaw } else { "missing" }
      LimitMemory = if ($limMemRaw) { $limMemRaw } else { "missing" }
    }) | Out-Null
  }
}

$monthlyCost = ($totalRequestCpu * [double]$costModel.hoursPerMonth * [double]$costModel.cpuCoreHour) +
  ($totalRequestMem * [double]$costModel.hoursPerMonth * [double]$costModel.memoryGiBHour)

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportPath = Join-Path $OutputDir "finops-baseline-$timestamp.md"
$lines = New-Object System.Collections.Generic.List[string]

Add-Line $lines "# FinOps Baseline and Result"
Add-Line $lines
Add-Line $lines "- Date: $(Get-Date -Format s)"
Add-Line $lines "- Namespace: $Namespace"
Add-Line $lines "- Currency: $($costModel.currency)"
Add-Line $lines "- Hours/month: $($costModel.hoursPerMonth)"
Add-Line $lines "- Apply policies: $ApplyPolicies"
Add-Line $lines
Add-Line $lines "## Cost Summary"
Add-Line $lines
Add-Line $lines "| Metric | Value |"
Add-Line $lines "| --- | ---: |"
Add-Line $lines ("| Requested CPU | {0:N3} cores |" -f $totalRequestCpu)
Add-Line $lines ("| Requested memory | {0:N3} GiB |" -f $totalRequestMem)
Add-Line $lines ("| Limited CPU | {0:N3} cores |" -f $totalLimitCpu)
Add-Line $lines ("| Limited memory | {0:N3} GiB |" -f $totalLimitMem)
Add-Line $lines ("| Estimated monthly requested cost | {0} {1:N2} |" -f $costModel.currency, $monthlyCost)
Add-Line $lines "| Containers missing requests | $missingRequests |"
Add-Line $lines "| Containers missing limits | $missingLimits |"
Add-Line $lines
Add-Line $lines "## Workload Detail"
Add-Line $lines
Add-Line $lines "| Deployment | Replicas | Container | Request CPU | Request Mem | Limit CPU | Limit Mem |"
Add-Line $lines "| --- | ---: | --- | ---: | ---: | ---: | ---: |"
foreach ($row in $rows) {
  Add-Line $lines "| $($row.Deployment) | $($row.Replicas) | $($row.Container) | $($row.RequestCpu) | $($row.RequestMemory) | $($row.LimitCpu) | $($row.LimitMemory) |"
}
Add-Line $lines

Add-Block $lines "Live deployments" (Invoke-Kubectl @("get", "deployments", "-n", $Namespace, "-o", "wide") -IgnoreErrors)
Add-Block $lines "Live pods" (Invoke-Kubectl @("get", "pods", "-n", $Namespace, "-o", "wide") -IgnoreErrors)
Add-Block $lines "Metrics API" (Invoke-Kubectl @("top", "pods", "-n", $Namespace) -IgnoreErrors)
Add-Block $lines "Resource policy dry run" (Invoke-Kubectl @("apply", "--dry-run=client", "-f", $policyManifest) -IgnoreErrors)
Add-Block $lines "Scale-to-zero policy dry run" (Invoke-Kubectl @("apply", "--dry-run=client", "-f", $scaleManifest) -IgnoreErrors)

if ($ApplyPolicies) {
  Add-Block $lines "Applied resource policy" (Invoke-Kubectl @("apply", "-f", $policyManifest) -IgnoreErrors)
  Add-Block $lines "Applied scale-to-zero policy" (Invoke-Kubectl @("apply", "-f", $scaleManifest) -IgnoreErrors)
} else {
  Add-Line $lines "## Policy Application"
  Add-Line $lines
  Add-Line $lines "Policies were validated with client dry-run only. Apply them with:"
  Add-Line $lines
  Add-Line $lines '```powershell'
  Add-Line $lines ".\finops\scripts\collect-finops.ps1 -ApplyPolicies"
  Add-Line $lines '```'
  Add-Line $lines
}

$lines | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "FinOps report written to $reportPath"
