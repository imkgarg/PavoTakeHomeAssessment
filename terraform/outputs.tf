output "gcp_secret_id" {
  description = "GCP Secret Manager secret resource ID"
  value       = data.google_secret_manager_secret.content_scanner_api_key.id
}

output "kubernetes_secret_name" {
  description = "Kubernetes secret created for the content scanner"
  value       = kubernetes_secret.content_scanner_api_key.metadata[0].name
}

output "kubernetes_secret_namespace" {
  description = "Namespace containing the content scanner secret"
  value       = kubernetes_secret.content_scanner_api_key.metadata[0].namespace
}
