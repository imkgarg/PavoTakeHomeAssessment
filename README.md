# Content Scanner

An HTTP service that scans tool result content for prompt injection patterns. Worker pods (e.g. exec-runner) call it before trusting external content. The service runs in `sandbox-namespace` alongside other sandbox workers.

## What it does

| Endpoint | Auth | Request | Response |
|----------|------|---------|----------|
| `GET /health` | None | — | `{"status":"ok"}` |
| `POST /scan` | `Authorization: Bearer <SCAN_API_KEY>` | `{"content":"<string>"}` | `{"safe":true/false,"reason":"<explanation>"}` |
| `GET /actuator/prometheus` | None (in-cluster) | — | Prometheus metrics |
| `GET /actuator/health/readiness` | None | — | K8s readiness probe |

- Listens on **port 8000**
- Reads `SCAN_API_KEY` from the environment (required at startup)
- Returns **401** for missing or invalid Bearer tokens on `/scan`
- Flags common prompt injection patterns (instruction override, jailbreak, fake system prompts, etc.)
- **Phase 1:** JSON structured logs, Prometheus metrics, 2 replicas, non-root container, NetworkPolicy in prod overlay

## Dev vs prod

The same codebase supports two deployment modes. The service and Kubernetes manifests are identical; only the **GCP project** that backs the API key differs.

| | **Dev mode** | **Prod mode** |
|---|-------------|---------------|
| **Purpose** | Local end-to-end testing on your own GCP account | Company production deployment |
| **GCP project** | Your personal project (e.g. `pavo-scanner-dev-*`) | `agentplatform-prod` |
| **Secret source** | GCP Secret Manager → Terraform → K8s secret | Same flow |
| **Terraform config** | `terraform/dev.tfvars` | Default variables in `main.tf` (no `dev.tfvars`) |
| **Setup script** | `./terraform/setup-gcp-dev.sh` | Requires company IAM access |
| **Test script** | `./terraform/test-terraform.sh` (auto-reads `dev.tfvars`) | `./terraform/test-terraform.sh` (without `dev.tfvars`) |
| **Billing** | Your own GCP billing account | Company billing |

> **Important:** `http://content-scanner:8000/scan` is a **private in-cluster URL** in both modes. It resolves only inside Kubernetes (within `sandbox-namespace` for the short hostname). There is **no public domain or Ingress** — you cannot curl this hostname from your laptop.

## Architecture

```mermaid
flowchart TB
    subgraph GCP["GCP Secret Manager"]
        DevSM["Dev: your-project<br/>content-scanner-api-key"]
        ProdSM["Prod: agentplatform-prod<br/>content-scanner-api-key"]
    end

    subgraph TF["Terraform"]
        DevVars["dev.tfvars<br/>(dev mode only)"]
        TFApply["terraform apply"]
    end

    subgraph K8s["Kubernetes — sandbox-namespace"]
        K8sSecret["Secret<br/>content-scanner-api-key"]
        Deploy["Deployment<br/>content-scanner"]
        SVC["ClusterIP Service<br/>content-scanner:8000"]
        Worker["Worker pods<br/>(e.g. exec-runner)"]
    end

    DevSM -.->|"dev mode"| DevVars
    ProdSM -.->|"prod mode"| TFApply
    DevVars --> TFApply
    TFApply -->|"read secret (never hardcoded)"| K8sSecret
    K8sSecret -->|"SCAN_API_KEY env var"| Deploy
    Deploy --> SVC
    Worker -->|"POST /scan<br/>http://content-scanner:8000/scan"| SVC
```

**Request flow (in-cluster):**

```
Worker pod  →  ClusterIP content-scanner:8000  →  Content Scanner pod  →  PromptInjectionScanner
```

**Secret flow (Terraform):**

```
GCP Secret Manager (content-scanner-api-key)
        ↓  terraform apply
Kubernetes Secret (sandbox-namespace/content-scanner-api-key)
        ↓  env var injection
content-scanner pod (SCAN_API_KEY)
```

## Project structure

```
.
├── src/                          # Spring Boot application (Java 21)
├── Dockerfile                    # Multi-stage build (Maven → JRE)
├── client/                       # Fail-closed HTTP client for exec-runner
├── k8s/
│   ├── base/                     # Deployment, Service, NetworkPolicy
│   └── overlays/
│       ├── local/                # Local dev (no NetworkPolicy, example exec-runner)
│       └── ghcr/                 # Registry-backed production image
├── .github/workflows/
│   ├── ci.yml                    # Tests + validation on PR
│   └── cd.yml                    # Push image to GHCR on merge to main
├── terraform/
│   ├── main.tf                   # GCP Secret Manager → K8s Secret
│   ├── providers.tf              # Google + Kubernetes providers
│   ├── outputs.tf                # Secret name/namespace outputs
│   ├── dev.tfvars.example        # Template for dev mode
│   ├── dev.tfvars                # Your dev project ID (gitignored, created by setup script)
│   ├── setup-gcp-dev.sh          # Bootstrap dev GCP project + secret
│   └── test-terraform.sh         # End-to-end Terraform test (dev or prod)
└── pom.xml
```

## Prerequisites

| Tool | Purpose |
|------|---------|
| Java 21+ | Local development |
| Maven 3.9+ | Build and test |
| Docker Desktop | Container builds and local K8s |
| kubectl | Kubernetes deployment |
| Terraform 1.5+ | Provision K8s secret from GCP |
| gcloud CLI | GCP authentication and Secret Manager |

Enable Kubernetes in **Docker Desktop → Settings → Kubernetes** for local cluster testing.

## CI

GitHub Actions runs on every push and pull request to `main`:

| Job | What it checks |
|-----|----------------|
| **Maven tests** | Service tests + exec-runner client tests |
| **Docker build** | `docker build` succeeds |
| **Terraform validate** | `terraform fmt -check`, `init`, `validate` |
| **Kubernetes manifests** | `kubeconform` on kustomize overlays (`local`, `ghcr`) |

CI does **not** run `terraform apply`, deploy to a cluster, or call GCP — those require credentials and are covered in the [testing plan](#testing-plan) for local/dev runs.

**CD** (`.github/workflows/cd.yml`) runs on merge to `main`: builds and pushes `ghcr.io/imkgarg/content-scanner:<sha>` and `:latest`.

**Branch protection** on `main` requires all CI jobs to pass and changes to go through a pull request.

Workflow files: [`.github/workflows/ci.yml`](.github/workflows/ci.yml), [`.github/workflows/cd.yml`](.github/workflows/cd.yml)

---

## Production v1 (Phase 1)

Phase 1 upgrades the assignment stub into an operable internal service:

| Capability | Implementation |
|------------|----------------|
| **Registry-backed deploys** | CD pushes to `ghcr.io/imkgarg/content-scanner`; deploy with `k8s/overlays/ghcr` |
| **Observability** | JSON logs, Prometheus metrics at `/actuator/prometheus`, K8s readiness/liveness probes |
| **Network isolation** | `NetworkPolicy` allows ingress only from `app: exec-runner` (enabled in `ghcr` overlay) |
| **exec-runner integration** | `client/` library with `FailClosedContentScanner` — blocks on unsafe content or scanner outage |

### Metrics

| Metric | Meaning |
|--------|---------|
| `scanner.requests.total` | Scan requests processed |
| `scanner.results.safe.total` | Content marked safe |
| `scanner.results.unsafe.total` | Injection detected |
| `scanner.auth.failures.total` | Invalid/missing Bearer token |

### exec-runner client (fail-closed)

```java
ContentScannerClient client = new ContentScannerClient(
    System.getenv("CONTENT_SCANNER_URL"),  // http://content-scanner:8000
    System.getenv("SCAN_API_KEY")
);
FailClosedContentScanner scanner = new FailClosedContentScanner(client);

// Throws ContentBlockedException if unsafe OR scanner unreachable
scanner.requireSafeContent(toolResult);
```

Add dependency: build/install `client/` module into exec-runner's classpath.

### Deploy locally (with example exec-runner pod)

```bash
docker build -t content-scanner:local .
kubectl apply -k k8s/overlays/local
```

### Deploy from registry (after CD publishes)

```bash
kubectl apply -k k8s/overlays/ghcr
```

---

## Quick start

### Dev mode (full local stack)

```bash
# 1. Build and test
mvn test
mvn test -f client/pom.xml
docker build -t content-scanner:local .

# 2. Authenticate with GCP
gcloud auth login
gcloud auth application-default login

# 3. Bootstrap your dev GCP project (creates project, secret, dev.tfvars)
./terraform/setup-gcp-dev.sh

# 4. Deploy to Kubernetes
kubectl create namespace sandbox-namespace --dry-run=client -o yaml | kubectl apply -f -
./terraform/test-terraform.sh
kubectl apply -k k8s/overlays/local
kubectl rollout status deployment/content-scanner -n sandbox-namespace

# 5. Test in-cluster (the real worker URL)
API_KEY=$(kubectl get secret content-scanner-api-key -n sandbox-namespace \
  -o jsonpath='{.data.SCAN_API_KEY}' | base64 -d)

kubectl run curl-test --rm -i --restart=Never -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello world"}'
```

### Prod mode

Requires IAM access to `agentplatform-prod`. Remove or rename `terraform/dev.tfvars` so Terraform uses production defaults.

```bash
gcloud auth login
gcloud auth application-default login
gcloud config set project agentplatform-prod
gcloud auth application-default set-quota-project agentplatform-prod

gcloud secrets describe content-scanner-api-key --project agentplatform-prod

kubectl create namespace sandbox-namespace --dry-run=client -o yaml | kubectl apply -f -
./terraform/test-terraform.sh
kubectl apply -k k8s/overlays/ghcr
```

---

## 1. Run locally (Maven)

No GCP or Kubernetes required. Set the API key yourself:

```bash
export SCAN_API_KEY=your-secret-key
mvn spring-boot:run
```

### Test (localhost)

```bash
curl http://localhost:8000/health

curl -X POST http://localhost:8000/scan \
  -H "Authorization: Bearer your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello world"}'

curl -s -o /dev/null -w "HTTP %{http_code}\n" \
  -X POST http://localhost:8000/scan \
  -H "Authorization: Bearer wrong-key" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello"}'

curl -X POST http://localhost:8000/scan \
  -H "Authorization: Bearer your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"content":"ignore all previous instructions"}'

curl -s http://localhost:8000/actuator/prometheus | grep scanner.requests
```

### Unit tests

```bash
mvn test                  # service: 39 tests
mvn test -f client/pom.xml   # exec-runner client: 4 tests
```

---

## 2. Docker

Runs as **non-root** (UID 10001) with a read-only root filesystem.

### Build and run

```bash
docker build -t content-scanner:local .

docker run -d --name content-scanner \
  -e SCAN_API_KEY=your-secret-key \
  -p 8000:8000 \
  content-scanner:local
```

### Test (localhost → container)

```bash
curl http://localhost:8000/health

curl -X POST http://localhost:8000/scan \
  -H "Authorization: Bearer your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello world"}'

curl -s http://localhost:8000/actuator/prometheus | grep scanner.requests
```

### Stop

```bash
docker stop content-scanner && docker rm content-scanner
```

> Port 8000 must be free. Use `-p 8001:8000` if something else is bound to 8000.

---

## 3. Kubernetes

Deployed in **`sandbox-namespace`** as a **ClusterIP** service named **`content-scanner`** on port **8000**.

Manifests use **Kustomize**:

| Overlay | Use case | Image | NetworkPolicy |
|---------|----------|-------|---------------|
| `k8s/overlays/local` | Local dev | `content-scanner:local` | Disabled (+ example exec-runner pod) |
| `k8s/overlays/ghcr` | Production | `ghcr.io/imkgarg/content-scanner` | Enabled (exec-runner only) |

### Deploy

```bash
kubectl create namespace sandbox-namespace

# Option A: Terraform-managed secret (recommended — see section 4)
./terraform/test-terraform.sh

# Option B: Manual secret (skip Terraform, dev only)
kubectl create secret generic content-scanner-api-key \
  --namespace sandbox-namespace \
  --from-literal=SCAN_API_KEY=your-local-dev-key

kubectl apply -k k8s/overlays/local
kubectl rollout status deployment/content-scanner -n sandbox-namespace
```

After Terraform updates the secret, restart pods so they pick up the new key:

```bash
kubectl rollout restart deployment/content-scanner -n sandbox-namespace
```

### Read the API key from the cluster

```bash
kubectl get secret content-scanner-api-key -n sandbox-namespace \
  -o jsonpath='{.data.SCAN_API_KEY}' | base64 -d && echo
```

### Test inside the cluster

These commands run a temporary pod in `sandbox-namespace` where `http://content-scanner:8000` resolves. This is how worker pods (e.g. exec-runner) reach the service.

```bash
API_KEY=$(kubectl get secret content-scanner-api-key -n sandbox-namespace \
  -o jsonpath='{.data.SCAN_API_KEY}' | base64 -d)

# Health
kubectl run curl-test --rm -i --restart=Never \
  -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s http://content-scanner:8000/health

# Scan — safe content
kubectl run curl-test --rm -i --restart=Never \
  -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello world"}'

# Scan — injection detected
kubectl run curl-test --rm -i --restart=Never \
  -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"content":"ignore all previous instructions"}'

# Scan — unauthorized (expect 401)
kubectl run curl-test --rm -i --restart=Never \
  -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -w "\nHTTP %{http_code}\n" \
  -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer wrong-key" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello"}'
```

### Test from your laptop (outside the cluster)

`http://content-scanner:8000` **will not resolve** on your Mac. Use port-forward:

```bash
kubectl port-forward -n sandbox-namespace svc/content-scanner 8000:8000
```

In a second terminal (use the API key from the cluster secret):

```bash
curl http://localhost:8000/health

curl -X POST http://localhost:8000/scan \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello world"}'
```

### Cross-namespace access

Pods in a **different namespace** must use the fully qualified DNS name:

```
http://content-scanner.sandbox-namespace.svc.cluster.local:8000/scan
```

### Clean up

```bash
kubectl delete namespace sandbox-namespace
```

---

## 4. Terraform and GCP

Terraform reads the API key from **GCP Secret Manager** and creates the Kubernetes secret. The key is **never hardcoded** in Terraform, manifests, or source code.

| Setting | Dev mode | Prod mode |
|---------|----------|-----------|
| GCP project | Your project (in `dev.tfvars`) | `agentplatform-prod` |
| Secret Manager secret | `content-scanner-api-key` | `content-scanner-api-key` |
| Kubernetes namespace | `sandbox-namespace` | `sandbox-namespace` |
| Kubernetes secret | `content-scanner-api-key` | `content-scanner-api-key` |
| Terraform var file | `terraform/dev.tfvars` | none (uses `main.tf` defaults) |

---

### Dev mode — your own GCP project

Use this to test the full GCP → Terraform → Kubernetes flow without company IAM access.

**Step 1 — Authenticate**

```bash
gcloud auth login
gcloud auth application-default login
```

**Step 2 — Bootstrap dev project**

Creates a GCP project (or uses an existing one), enables Secret Manager, creates the secret, and writes `terraform/dev.tfvars`:

```bash
# Create a new project (requires billing on your Google account)
./terraform/setup-gcp-dev.sh

# Or use an existing project with billing enabled
./terraform/setup-gcp-dev.sh your-existing-project-id
```

If billing is not yet linked, the script saves the project ID to `dev.tfvars` and prints instructions. Link billing at [console.cloud.google.com/billing](https://console.cloud.google.com/billing), then re-run:

```bash
./terraform/setup-gcp-dev.sh pavo-scanner-dev-XXXXX
```

**Step 3 — Run Terraform end-to-end test**

```bash
./terraform/test-terraform.sh
```

This script:
1. Runs `terraform init` and `validate`
2. Reads `terraform/dev.tfvars` for your project ID
3. Verifies the GCP secret exists
4. Imports an existing K8s secret if needed
5. Runs `terraform plan` and `apply`
6. Confirms K8s secret byte length matches GCP (without printing the key)

**Step 4 — Restart the scanner pod**

```bash
kubectl rollout restart deployment/content-scanner -n sandbox-namespace
```

**Step 5 — Verify the GCP key works in-cluster**

```bash
API_KEY=$(kubectl get secret content-scanner-api-key -n sandbox-namespace \
  -o jsonpath='{.data.SCAN_API_KEY}' | base64 -d)

kubectl run curl-test --rm -i --restart=Never -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello world"}'
```

**Manual apply (alternative to test script):**

```bash
cd terraform
terraform init
terraform plan -var-file=dev.tfvars
terraform apply -var-file=dev.tfvars
```

---

### Prod mode — `agentplatform-prod`

Used in the real deployment. Requires company IAM access to read Secret Manager in `agentplatform-prod`.

**Step 1 — Authenticate and set project**

```bash
gcloud auth login
gcloud auth application-default login
gcloud config set project agentplatform-prod
gcloud auth application-default set-quota-project agentplatform-prod
```

**Step 2 — Verify secret access**

```bash
gcloud secrets describe content-scanner-api-key --project agentplatform-prod
```

If you get `PERMISSION_DENIED`, request `roles/secretmanager.secretAccessor` on the project from your admin.

**Step 3 — Apply Terraform**

Ensure `terraform/dev.tfvars` is absent or renamed so prod defaults are used:

```bash
mv terraform/dev.tfvars terraform/dev.tfvars.bak   # if present

./terraform/test-terraform.sh
```

Or manually:

```bash
cd terraform
terraform init
terraform plan
terraform apply
```

**Step 4 — Deploy and restart**

```bash
kubectl apply -k k8s/overlays/ghcr
kubectl rollout status deployment/content-scanner -n sandbox-namespace
```

---

### Terraform outputs

After a successful apply:

```
gcp_secret_id               = projects/<project>/secrets/content-scanner-api-key
kubernetes_secret_name      = content-scanner-api-key
kubernetes_secret_namespace = sandbox-namespace
```

---

## URL reference

| Where you call from | URL | Works? |
|---------------------|-----|--------|
| Pod in `sandbox-namespace` | `http://content-scanner:8000/scan` | Yes — intended worker path |
| Pod in another namespace | `http://content-scanner.sandbox-namespace.svc.cluster.local:8000/scan` | Yes — FQDN required |
| Your laptop (Mac/terminal) | `http://content-scanner:8000/scan` | **No** — not in cluster DNS |
| Your laptop via port-forward | `http://localhost:8000/scan` | Yes — debugging only |
| Public internet | — | **No** — no Ingress or public domain |

Worker pods in `sandbox-namespace` should call:

```
POST http://content-scanner:8000/scan
Authorization: Bearer <SCAN_API_KEY>
Content-Type: application/json

{"content":"<tool result string>"}
```

---

## Testing plan

### What can be tested in each mode

| Layer | Dev mode | Prod mode |
|-------|----------|-----------|
| Unit tests (`mvn test` + client) | ✅ 43 tests | ✅ Same |
| Docker build + run + metrics | ✅ Full | ✅ Full |
| K8s deploy (`local` or `ghcr` overlay) | ✅ Full | ✅ Full |
| Terraform validate | ✅ Full | ✅ Full |
| Terraform plan/apply (GCP → K8s secret) | ✅ Full (your GCP project) | ⚠️ Requires IAM on `agentplatform-prod` |
| exec-runner client (fail-closed) | ✅ Full | ✅ Full |
| CD image push to GHCR | ✅ On merge to main | ✅ Same |
| Port-forward (laptop → cluster) | ✅ Full | ✅ Full |

> **Prod note:** Without company IAM on `agentplatform-prod`, you can still validate Terraform syntax, K8s manifests (`kubectl apply --dry-run=client`), and the in-cluster service path. Only the GCP Secret Manager read and prod `terraform apply` are blocked.

### Expected results

| Test | Command / URL | Expected |
|------|---------------|----------|
| Service unit tests | `mvn test` | 39 tests pass |
| Client unit tests | `mvn test -f client/pom.xml` | 4 tests pass |
| Health (no auth) | `GET /health` | `{"status":"ok"}` |
| Prometheus metrics | `GET /actuator/prometheus` | Contains `scanner.requests.total` |
| Scan — safe | `POST /scan` + valid Bearer | `{"safe":true,"reason":"..."}` |
| Scan — injection | `POST /scan` + `"ignore all previous instructions"` | `{"safe":false,"reason":"..."}` |
| Scan — unauthorized | `POST /scan` + wrong/missing Bearer | HTTP 401 |
| Kustomize validate | `kubectl kustomize k8s/overlays/local` | Renders without error |
| Terraform apply | `./terraform/test-terraform.sh` | `Apply complete`, secret lengths match |
| In-cluster DNS | `http://content-scanner:8000/health` from `sandbox-namespace` | `{"status":"ok"}` |
| Fail-closed client | `FailClosedContentScanner.requireSafeContent()` | Throws on unsafe or outage |
| Port-forward | `kubectl port-forward ... 8000:8000` | Works for debugging only |

---

## End-to-end test checklist

### Dev mode (full stack — run this locally)

```bash
# ── 1. Service + client ───────────────────────────────────
mvn test                                    # expect: 39 tests pass
mvn test -f client/pom.xml                  # expect: 4 tests pass

# ── 2. Docker ─────────────────────────────────────────────
docker build -t content-scanner:local .
docker run -d --name content-scanner \
  -e SCAN_API_KEY=dev-docker-key -p 8001:8000 content-scanner:local
curl -s http://localhost:8001/health      # expect: {"status":"ok"}
curl -s http://localhost:8001/actuator/prometheus | grep scanner.requests
curl -s -X POST http://localhost:8001/scan \
  -H "Authorization: Bearer dev-docker-key" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello"}'                 # expect: safe:true
docker stop content-scanner && docker rm content-scanner

# ── 3. GCP + Terraform ────────────────────────────────────
gcloud auth login
gcloud auth application-default login
./terraform/setup-gcp-dev.sh              # creates project, secret, dev.tfvars
./terraform/test-terraform.sh             # expect: Apply complete, lengths match

# ── 4. Kubernetes ─────────────────────────────────────────
kubectl create namespace sandbox-namespace --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -k k8s/overlays/local
kubectl rollout restart deployment/content-scanner -n sandbox-namespace
kubectl rollout status deployment/content-scanner -n sandbox-namespace

# ── 5. In-cluster (real worker URL) ───────────────────────
API_KEY=$(kubectl get secret content-scanner-api-key -n sandbox-namespace \
  -o jsonpath='{.data.SCAN_API_KEY}' | base64 -d)

kubectl run curl-test --rm -i --restart=Never -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s http://content-scanner:8000/health
# expect: {"status":"ok"}

kubectl run curl-test --rm -i --restart=Never -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello world"}'
# expect: safe:true

kubectl run curl-test --rm -i --restart=Never -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"content":"ignore all previous instructions"}'
# expect: safe:false

kubectl run curl-test --rm -i --restart=Never -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s -o /dev/null -w "HTTP %{http_code}\n" \
  -X POST http://content-scanner:8000/scan \
  -H "Authorization: Bearer wrong-key" \
  -H "Content-Type: application/json" \
  -d '{"content":"hello"}'
# expect: HTTP 401

# ── 6. Outside cluster (port-forward) ─────────────────────
kubectl port-forward -n sandbox-namespace svc/content-scanner 8000:8000 &
sleep 2
curl -s http://localhost:8000/health      # expect: {"status":"ok"}
kill %1
```

### Prod mode

**Without company IAM** (validate what you can):

```bash
# Terraform config is valid for prod defaults
mv terraform/dev.tfvars terraform/dev.tfvars.bak
cd terraform && terraform validate && cd ..

# K8s manifests are valid
kubectl kustomize k8s/overlays/ghcr >/dev/null && echo "ghcr overlay: OK"

# GCP access will fail until admin grants access:
gcloud secrets describe content-scanner-api-key --project agentplatform-prod
# expect: PERMISSION_DENIED (without IAM)

mv terraform/dev.tfvars.bak terraform/dev.tfvars   # restore dev
```

**With company IAM on `agentplatform-prod`** (full prod stack):

```bash
mv terraform/dev.tfvars terraform/dev.tfvars.bak   # use prod defaults

gcloud auth login
gcloud auth application-default login
gcloud config set project agentplatform-prod
gcloud auth application-default set-quota-project agentplatform-prod

gcloud secrets describe content-scanner-api-key --project agentplatform-prod  # must succeed
./terraform/test-terraform.sh                     # expect: Apply complete
kubectl apply -k k8s/overlays/ghcr
kubectl rollout status deployment/content-scanner -n sandbox-namespace

# Same in-cluster tests as dev (use API_KEY from cluster secret)
API_KEY=$(kubectl get secret content-scanner-api-key -n sandbox-namespace \
  -o jsonpath='{.data.SCAN_API_KEY}' | base64 -d)

kubectl run curl-test --rm -i --restart=Never -n sandbox-namespace \
  --image=curlimages/curl:latest \
  -- curl -s http://content-scanner:8000/health

mv terraform/dev.tfvars.bak terraform/dev.tfvars   # restore dev
```


## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `docker.sock: connect: no such file` | Docker daemon not running | Start Docker Desktop |
| `port 8000 already allocated` | Another process/container on 8000 | `lsof -i :8000`, stop conflicting process |
| `kubectl ... connection refused` | No Kubernetes cluster | Enable K8s in Docker Desktop |
| `content-scanner: no such host` on Mac | Calling in-cluster URL from laptop | Use `kubectl port-forward` or in-cluster curl pod |
| `content-scanner: no such host` in pod | Wrong namespace | Use FQDN or deploy caller to `sandbox-namespace` |
| `PERMISSION_DENIED` on `agentplatform-prod` | No company IAM access | Use dev mode with `./terraform/setup-gcp-dev.sh` |
| Secret Manager billing error | Billing not enabled on project | Link billing at [console.cloud.google.com/billing](https://console.cloud.google.com/billing), re-run setup |
| Pod `CreateContainerConfigError` | Missing K8s secret | Run `./terraform/test-terraform.sh` or create secret manually |
| Scan returns 401 after Terraform apply | Pod still using old secret | `kubectl rollout restart deployment/content-scanner -n sandbox-namespace` |
| `test-terraform.sh` uses wrong project | Stale `dev.tfvars` | Check `terraform/dev.tfvars` or set `GCP_PROJECT=your-project ./terraform/test-terraform.sh` |
| curl pod blocked on `ghcr` overlay | NetworkPolicy active | Use `exec-runner` labeled pod or `local` overlay for debug |
| Image pull error on `ghcr` overlay | GHCR package private or missing | Run CD workflow on main first; make package public or configure `imagePullSecrets` |
