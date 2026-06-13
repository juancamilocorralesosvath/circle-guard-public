param(
  [string]$Namespace = "chaos-mesh",
  [string]$Runtime = "containerd",
  [string]$SocketPath = "/run/containerd/containerd.sock"
)

$ErrorActionPreference = "Stop"

Write-Host "Installing Chaos Mesh into namespace '$Namespace'..."

kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -
helm repo add chaos-mesh https://charts.chaos-mesh.org | Out-Host
helm repo update | Out-Host
helm upgrade --install chaos-mesh chaos-mesh/chaos-mesh `
  --namespace $Namespace `
  --set chaosDaemon.runtime=$Runtime `
  --set chaosDaemon.socketPath=$SocketPath | Out-Host

kubectl rollout status deployment/chaos-controller-manager -n $Namespace --timeout=180s
kubectl get pods -n $Namespace

Write-Host "Chaos Mesh installation completed."
