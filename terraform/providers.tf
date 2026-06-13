provider "google" {
  project = var.gcp_project_id
}

provider "kubernetes" {
  config_path = "~/.kube/config"
}
