terraform {
  required_version = ">= 1.6.0"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.27"
    }
  }

  backend "remote" {
    organization = "circleguard"

    workspaces {
      name = "circleguard-staging"
    }
  }
}

provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

module "namespace" {
  source      = "../../modules/namespace"
  namespace   = var.namespace
  environment = "staging"

  quota_cpu_request = "4"
  quota_cpu_limit   = "8"
  quota_mem_request = "4Gi"
  quota_mem_limit   = "8Gi"
  quota_pods        = "40"
}

module "configmap" {
  source      = "../../modules/configmap"
  name        = "circleguard-config"
  namespace   = module.namespace.namespace_name
  environment = "staging"

  data = {
    DB_HOST                                              = "postgres-service"
    DB_PORT                                              = "5432"
    REDIS_HOST                                           = "redis-service"
    REDIS_PORT                                           = "6379"
    NEO4J_HOST                                           = "neo4j-service"
    NEO4J_PORT                                           = "7687"
    KAFKA_BOOTSTRAP_SERVERS                              = "kafka-service:9092"
    LDAP_HOST                                            = "openldap-service"
    LDAP_PORT                                            = "389"
    LDAP_BASE                                            = "dc=circleguard,dc=edu"
    AUTH_API_URL                                         = "http://circleguard-auth-service:8180"
    IDENTITY_SERVICE_URL                                 = "http://circleguard-identity-service:8083"
    OTEL_EXPORTER_OTLP_ENDPOINT                          = "http://host.docker.internal:4317"
    JWT_EXPIRATION                                       = "3600000"
    QR_EXPIRATION                                        = "300000"
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE           = "25"
    SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT          = "20000"
    SPRING_KAFKA_PRODUCER_PROPERTIES_DELIVERY_TIMEOUT_MS = "30000"
    SPRING_KAFKA_PRODUCER_PROPERTIES_REQUEST_TIMEOUT_MS  = "10000"
    SPRING_KAFKA_PRODUCER_PROPERTIES_LINGER_MS           = "20"
    SPRING_KAFKA_PRODUCER_BATCH_SIZE                     = "16384"
  }
}

module "auth_service_account" {
  source    = "../../modules/service-account"
  name      = "circleguard-auth"
  namespace = module.namespace.namespace_name
}

module "gateway_service_account" {
  source    = "../../modules/service-account"
  name      = "circleguard-gateway"
  namespace = module.namespace.namespace_name
}

module "identity_service_account" {
  source    = "../../modules/service-account"
  name      = "circleguard-identity"
  namespace = module.namespace.namespace_name
}

module "form_service_account" {
  source    = "../../modules/service-account"
  name      = "circleguard-form"
  namespace = module.namespace.namespace_name
}

module "notification_service_account" {
  source    = "../../modules/service-account"
  name      = "circleguard-notification"
  namespace = module.namespace.namespace_name
}

module "promotion_service_account" {
  source    = "../../modules/service-account"
  name      = "circleguard-promotion"
  namespace = module.namespace.namespace_name
}
