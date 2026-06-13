param(
  [ValidateSet("gateway-pod-kill", "promotion-network-delay")]
  [string]$Experiment = "gateway-pod-kill",
  [string]$Namespace = "circleguard-dev",
  [switch]$DryRun,
  [switch]$AllowKubernetesFallback,
  [string]$OutputDir
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..")
if (-not $OutputDir) {
  $OutputDir = Join-Path $repoRoot "chaos\results"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$manifest = Join-Path $repoRoot "chaos\manifests\$Experiment.yaml"
if (-not (Test-Path $manifest)) {
  throw "Experiment manifest not found: $manifest"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportPath = Join-Path $OutputDir "chaos-$Experiment-$timestamp.md"
$lines = New-Object System.Collections.Generic.List[string]

function Add-Line([string]$value = "") {
  $script:lines.Add($value) | Out-Null
}

function Add-Block([string]$title, [string]$content) {
  Add-Line "## $title"
  Add-Line '```text'
  if ([string]::IsNullOrWhiteSpace($content)) {
    Add-Line "(no output)"
  } else {
    $content.TrimEnd() -split "`r?`n" | ForEach-Object { Add-Line $_ }
  }
  Add-Line '```'
  Add-Line
}

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

function Get-ChaosApiInstalled {
  $resources = Invoke-Kubectl @("api-resources", "--api-group=chaos-mesh.org") -IgnoreErrors
  return ($resources -match "podchaos" -or $resources -match "networkchaos")
}

function Add-ClusterSnapshot([string]$Prefix) {
  Add-Block "$Prefix deployments" (Invoke-Kubectl @("get", "deploy", "-n", $Namespace, "-o", "wide") -IgnoreErrors)
  Add-Block "$Prefix pods" (Invoke-Kubectl @("get", "pods", "-n", $Namespace, "-o", "wide") -IgnoreErrors)
  Add-Block "$Prefix endpoints" (Invoke-Kubectl @("get", "endpoints", "-n", $Namespace) -IgnoreErrors)
  Add-Block "$Prefix recent events" (Invoke-Kubectl @("get", "events", "-n", $Namespace, "--sort-by=.lastTimestamp") -IgnoreErrors)
  Add-Block "$Prefix metrics" (Invoke-Kubectl @("top", "pods", "-n", $Namespace) -IgnoreErrors)
}

Add-Line "# Chaos Engineering Result"
Add-Line
Add-Line "- Date: $(Get-Date -Format s)"
Add-Line "- Namespace: $Namespace"
Add-Line "- Experiment: $Experiment"
Add-Line "- Dry run: $DryRun"
Add-Line "- Kubernetes fallback allowed: $AllowKubernetesFallback"
Add-Line

Add-ClusterSnapshot "Before"

$chaosInstalled = Get-ChaosApiInstalled
Add-Line "## Execution"
Add-Line

if ($DryRun) {
  Add-Line "Chaos execution skipped because DryRun was requested."
} elseif ($chaosInstalled) {
  Add-Line "Chaos Mesh CRDs detected. Applying manifest: $manifest"
  Add-Block "Chaos apply" (Invoke-Kubectl @("apply", "-f", $manifest, "-n", $Namespace) -IgnoreErrors)
  if ($Experiment -eq "promotion-network-delay") {
    Start-Sleep -Seconds 75
  } else {
    Start-Sleep -Seconds 45
  }
  Add-Block "Chaos cleanup" (Invoke-Kubectl @("delete", "-f", $manifest, "-n", $Namespace, "--ignore-not-found=true") -IgnoreErrors)
} elseif ($AllowKubernetesFallback -and $Experiment -eq "gateway-pod-kill") {
  Add-Line "Chaos Mesh CRDs were not found. Running Kubernetes-native pod kill fallback for the same target."
  $podName = (Invoke-Kubectl @("get", "pods", "-n", $Namespace, "-l", "app=circleguard-gateway-service", "-o", "jsonpath={.items[0].metadata.name}")).Trim()
  Add-Line "Deleting pod: $podName"
  Add-Block "Fallback pod delete" (Invoke-Kubectl @("delete", "pod", $podName, "-n", $Namespace) -IgnoreErrors)
  Add-Block "Gateway rollout recovery" (Invoke-Kubectl @("rollout", "status", "deployment/circleguard-gateway-service", "-n", $Namespace, "--timeout=180s") -IgnoreErrors)
} else {
  Add-Line "Chaos Mesh CRDs were not found. Install with chaos/scripts/install-chaos-mesh.ps1 or rerun gateway-pod-kill with -AllowKubernetesFallback."
}
Add-Line

Add-ClusterSnapshot "After"

if ($Experiment -eq "gateway-pod-kill") {
  Add-Block "Final gateway rollout" (Invoke-Kubectl @("rollout", "status", "deployment/circleguard-gateway-service", "-n", $Namespace, "--timeout=180s") -IgnoreErrors)
} elseif ($Experiment -eq "promotion-network-delay") {
  Add-Block "Final promotion rollout" (Invoke-Kubectl @("rollout", "status", "deployment/circleguard-promotion-service", "-n", $Namespace, "--timeout=180s") -IgnoreErrors)
}

$lines | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "Chaos report written to $reportPath"
