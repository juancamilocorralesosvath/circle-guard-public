variable "namespace" {
  description = "Name of the Kubernetes namespace to create"
  type        = string
}

variable "environment" {
  description = "Environment label (dev, staging, prod)"
  type        = string
}

variable "quota_cpu_request" {
  description = "Total CPU requests allowed in the namespace"
  type        = string
  default     = "2"
}

variable "quota_cpu_limit" {
  description = "Total CPU limits allowed in the namespace"
  type        = string
  default     = "4"
}

variable "quota_mem_request" {
  description = "Total memory requests allowed in the namespace"
  type        = string
  default     = "2Gi"
}

variable "quota_mem_limit" {
  description = "Total memory limits allowed in the namespace"
  type        = string
  default     = "4Gi"
}

variable "quota_pods" {
  description = "Maximum number of pods in the namespace"
  type        = string
  default     = "20"
}
