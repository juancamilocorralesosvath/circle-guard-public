variable "name" {
  description = "Name of the ConfigMap"
  type        = string
}

variable "namespace" {
  description = "Namespace where the ConfigMap is created"
  type        = string
}

variable "environment" {
  description = "Environment label"
  type        = string
}

variable "data" {
  description = "Key-value pairs to store in the ConfigMap"
  type        = map(string)
}
