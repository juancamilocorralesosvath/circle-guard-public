output "configmap_name" {
  description = "Name of the created ConfigMap"
  value       = kubernetes_config_map.this.metadata[0].name
}
