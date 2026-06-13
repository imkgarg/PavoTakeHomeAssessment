#!/usr/bin/env bash
set -euo pipefail

# Bootstraps a personal GCP project for local Terraform testing.
#
# Usage:
#   ./setup-gcp-dev.sh                          # creates pavo-scanner-dev-<suffix>
#   ./setup-gcp-dev.sh my-existing-project-id   # use an existing project
#
# Requires: gcloud, billing enabled on your Google account

SECRET_NAME="content-scanner-api-key"
SECRET_VALUE="${SCAN_API_KEY:-local-dev-scanner-key-$(openssl rand -hex 8)}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "Usage: $0 [GCP_PROJECT_ID]"
  exit 0
fi

if [[ -n "${1:-}" ]]; then
  GCP_PROJECT="$1"
  CREATE_PROJECT=false
else
  SUFFIX="$(date +%s | tail -c 6)"
  GCP_PROJECT="pavo-scanner-dev-${SUFFIX}"
  CREATE_PROJECT=true
fi

echo "==> Using GCP project: $GCP_PROJECT"

if ! gcloud auth list --filter=status:ACTIVE --format='value(account)' | grep -q .; then
  echo "Run: gcloud auth login"
  exit 1
fi

if [[ "$CREATE_PROJECT" == true ]]; then
  echo "==> Creating project (requires billing on your account)"
  gcloud projects create "$GCP_PROJECT" --name="Pavo Content Scanner Dev"
fi

echo "==> Setting active gcloud project"
gcloud config set project "$GCP_PROJECT"

echo "==> Linking billing (required for Secret Manager)"
BILLING_ACCOUNT="$(gcloud billing accounts list --format='value(name)' 2>/dev/null | head -1)"
if [[ -z "$BILLING_ACCOUNT" ]]; then
  echo
  echo "ERROR: No billing account found on this Google account."
  echo "Secret Manager requires billing (free tier is fine)."
  echo
  echo "1. Open https://console.cloud.google.com/billing"
  echo "2. Create or link a billing account"
  echo "3. Link it to project: $GCP_PROJECT"
  echo "   https://console.cloud.google.com/billing/linkedaccount?project=$GCP_PROJECT"
  echo "4. Re-run: ./setup-gcp-dev.sh $GCP_PROJECT"
  echo
  cat > "$(dirname "$0")/dev.tfvars" <<EOF
gcp_project_id = "$GCP_PROJECT"
EOF
  echo "Saved project ID to terraform/dev.tfvars for when billing is ready."
  exit 1
fi

if ! gcloud billing projects describe "$GCP_PROJECT" --format='value(billingEnabled)' 2>/dev/null | grep -q True; then
  gcloud billing projects link "$GCP_PROJECT" --billing-account="$BILLING_ACCOUNT"
fi

echo "==> Enabling Secret Manager API"
gcloud services enable secretmanager.googleapis.com --project="$GCP_PROJECT"

echo "==> Creating Secret Manager secret: $SECRET_NAME"
if gcloud secrets describe "$SECRET_NAME" --project="$GCP_PROJECT" >/dev/null 2>&1; then
  echo "Secret already exists — adding new version"
  printf '%s' "$SECRET_VALUE" | gcloud secrets versions add "$SECRET_NAME" --data-file=- --project="$GCP_PROJECT"
else
  printf '%s' "$SECRET_VALUE" | gcloud secrets create "$SECRET_NAME" --data-file=- --project="$GCP_PROJECT"
fi

echo "==> Configuring Application Default Credentials quota project"
gcloud auth application-default set-quota-project "$GCP_PROJECT" 2>/dev/null || \
  echo "Run 'gcloud auth application-default login' if ADC is missing, then re-run this script."

cat > "$(dirname "$0")/dev.tfvars" <<EOF
gcp_project_id = "$GCP_PROJECT"
EOF

echo
echo "Done."
echo "  GCP project:  $GCP_PROJECT"
echo "  Secret:       $SECRET_NAME"
echo "  dev.tfvars:   terraform/dev.tfvars"
echo
echo "Next:"
echo "  GCP_PROJECT=$GCP_PROJECT ./terraform/test-terraform.sh"
