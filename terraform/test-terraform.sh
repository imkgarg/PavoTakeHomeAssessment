#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF_DIR="$ROOT/terraform"
NAMESPACE="sandbox-namespace"
SECRET_NAME="content-scanner-api-key"
GCP_PROJECT="agentplatform-prod"
GCP_SECRET="content-scanner-api-key"

echo "==> 1) Terraform init + validate"
cd "$TF_DIR"
terraform init -input=false
terraform validate

echo
echo "==> 2) Check GCP credentials"
if ! gcloud auth application-default print-access-token >/dev/null 2>&1; then
  echo "GCP application-default credentials not found."
  echo "Run these in your terminal (browser login required):"
  echo "  gcloud auth login"
  echo "  gcloud auth application-default login"
  echo "  gcloud config set project $GCP_PROJECT"
  exit 1
fi

echo "GCP credentials: OK"

echo
echo "==> 3) Verify Secret Manager secret is readable"
gcloud secrets describe "$GCP_SECRET" --project "$GCP_PROJECT" >/dev/null
echo "Secret '$GCP_SECRET' exists in project '$GCP_PROJECT'"

echo
echo "==> 4) Ensure Kubernetes namespace exists"
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

echo
echo "==> 5) Import existing secret if needed (local dev reruns)"
if kubectl get secret "$SECRET_NAME" -n "$NAMESPACE" >/dev/null 2>&1; then
  if ! terraform state show kubernetes_secret.content_scanner_api_key >/dev/null 2>&1; then
    terraform import kubernetes_secret.content_scanner_api_key "$NAMESPACE/$SECRET_NAME"
  fi
fi

echo
echo "==> 6) Terraform plan"
terraform plan

echo
echo "==> 7) Terraform apply"
terraform apply

echo
echo "==> 8) Verify Kubernetes secret was created from GCP"
kubectl get secret "$SECRET_NAME" -n "$NAMESPACE" -o jsonpath='{.metadata.labels.app}{"\n"}'
echo "(secret exists; value intentionally not printed)"

echo
echo "==> 9) Optional: compare key length with GCP (not the value)"
K8S_LEN=$(kubectl get secret "$SECRET_NAME" -n "$NAMESPACE" -o jsonpath='{.data.SCAN_API_KEY}' | base64 -d | wc -c | tr -d ' ')
GCP_LEN=$(gcloud secrets versions access latest --secret="$GCP_SECRET" --project="$GCP_PROJECT" | wc -c | tr -d ' ')
echo "GCP secret length:  $GCP_LEN bytes"
echo "K8s secret length: $K8S_LEN bytes"
if [[ "$K8S_LEN" == "$GCP_LEN" ]]; then
  echo "Lengths match — Terraform wired GCP -> Kubernetes correctly."
else
  echo "WARNING: lengths differ."
  exit 1
fi

echo
echo "All Terraform checks passed."
