resource "kubernetes_config_map" "this" {
  metadata {
    name      = var.name
    namespace = var.namespace
    labels = {
      managed-by  = "terraform"
      project     = "circleguard"
      environment = var.environment
    }
  }

  data = var.data
}
