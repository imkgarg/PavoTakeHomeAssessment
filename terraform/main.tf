terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.35"
    }
  }
}

variable "gcp_project_id" {
  description = "GCP project that owns the Secret Manager secret"
  type        = string
  default     = "agentplatform-prod"
}

variable "secret_name" {
  description = "Secret Manager secret name for the content scanner API key"
  type        = string
  default     = "content-scanner-api-key"
}

variable "kubernetes_namespace" {
  description = "Namespace where the content scanner runs"
  type        = string
  default     = "sandbox-namespace"
}

variable "kubernetes_secret_name" {
  description = "Kubernetes secret name injected into the content-scanner pod"
  type        = string
  default     = "content-scanner-api-key"
}

data "google_secret_manager_secret" "content_scanner_api_key" {
  project   = var.gcp_project_id
  secret_id = var.secret_name
}

data "google_secret_manager_secret_version" "content_scanner_api_key" {
  secret  = data.google_secret_manager_secret.content_scanner_api_key.id
  version = "latest"
}

resource "kubernetes_secret" "content_scanner_api_key" {
  metadata {
    name      = var.kubernetes_secret_name
    namespace = var.kubernetes_namespace
    labels = {
      app = "content-scanner"
    }
  }

  data = {
    SCAN_API_KEY = data.google_secret_manager_secret_version.content_scanner_api_key.secret_data
  }

  type = "Opaque"
}
