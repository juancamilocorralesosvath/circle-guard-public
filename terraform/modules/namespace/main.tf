resource "kubernetes_namespace" "this" {
  metadata {
    name = var.namespace
    labels = {
      environment = var.environment
      managed-by  = "terraform"
      project     = "circleguard"
    }
  }
}

resource "kubernetes_resource_quota" "this" {
  metadata {
    name      = "${var.namespace}-quota"
    namespace = kubernetes_namespace.this.metadata[0].name
  }

  spec {
    hard = {
      "requests.cpu"    = var.quota_cpu_request
      "limits.cpu"      = var.quota_cpu_limit
      "requests.memory" = var.quota_mem_request
      "limits.memory"   = var.quota_mem_limit
      "pods"            = var.quota_pods
    }
  }
}
