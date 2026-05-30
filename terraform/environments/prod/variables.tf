variable "namespace" {
  description = "Kubernetes namespace for production environment"
  type        = string
  default     = "circleguard-prod"
}

variable "kubeconfig_path" {
  description = "Path to the kubeconfig file"
  type        = string
  default     = "~/.kube/config"
}

variable "kube_context" {
  description = "Kubernetes context to use"
  type        = string
  default     = "docker-desktop"
}
