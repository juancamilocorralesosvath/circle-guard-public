# CircleGuard — Agile Methodology

## 1. Tool: GitHub Projects (Kanban)

**Board:** [CircleGuard Final Project — Kanban](https://github.com/juancamilocorralesosvath/circle-guard-public/projects)

Columns:
| Column | Purpose |
|--------|---------|
| Backlog | User stories not yet started |
| In Progress | Actively being worked on |
| In Review | PR open / awaiting review |
| Done | Merged and verified |

---

## 2. Branching Strategy: GitHub Flow

```
main
  └── feature/phase-1-agile-setup
  └── feature/phase-2-terraform-iac
  └── feature/phase-3-design-patterns
  └── feature/phase-4-cicd-enhancements
  └── feature/phase-5-testing-enhancements
  └── feature/phase-6-observability-elk-tracing
  └── feature/phase-7-security-rbac
  └── feature/phase-8-documentation
```

**Rules:**
- `main` is always deployable.
- All work happens on short-lived `feature/` branches.
- Every branch requires a Pull Request before merging into `main`.
- No direct pushes to `main`.
- Branch names follow: `feature/<phase>-<short-description>` or `fix/<short-description>`.
- Commits follow Conventional Commits: `feat:`, `fix:`, `docs:`, `test:`, `chore:`.

---

## 3. Sprint Structure

### Sprint 1 — Infrastructure & Foundations
**Duration:** Week 1–2  
**Goal:** Deliver the structural backbone: Agile setup, Terraform IaC, and Design Patterns.

| # | User Story | Acceptance Criteria |
|---|-----------|---------------------|
| US-01 | As a team, I want a documented agile process so stakeholders can track progress | GitHub Projects board exists with Backlog/In Progress/Done columns; AGILE.md committed |
| US-02 | As an operator, I want Kubernetes infrastructure described as Terraform code so environments are reproducible | `terraform plan` runs cleanly for dev, staging, and prod; remote backend configured |
| US-03 | As a developer, I want Circuit Breaker on the gateway so downstream failures don't cascade | `/actuator/circuitbreakers` returns state; fallback response returns HTTP 503 with message |
| US-04 | As a developer, I want External Configuration documented so I know all env vars come from ConfigMaps | All 6 services use ConfigMap-sourced env vars; documented in DESIGN_PATTERNS.md |

### Sprint 2 — Quality, Observability & Security
**Duration:** Week 3–4  
**Goal:** Harden CI/CD, expand observability, add security scanning and documentation.

| # | User Story | Acceptance Criteria |
|---|-----------|---------------------|
| US-05 | As a CI engineer, I want SonarQube analysis in the pipeline so code quality is enforced | Quality Gate passes on every build; pipeline fails if gate is red |
| US-06 | As a security engineer, I want Trivy scanning on every Docker image so vulnerabilities are visible | Trivy output appears in Jenkins console per image; HIGH/CRITICAL CVEs listed |
| US-07 | As a team, I want Slack notifications on pipeline failure so we're alerted immediately | Slack message sent to #ci-alerts on failure and success |
| US-08 | As a release manager, I want a production approval gate so no unintended deploys reach prod | Jenkins `input` step pauses before prod deploy; requires manual confirmation |
| US-09 | As a QA engineer, I want OWASP ZAP security scan results published in Jenkins | ZAP HTML report accessible from Jenkins build page |
| US-10 | As an operator, I want ELK Stack ingesting CircleGuard logs so I can search them in Kibana | Kibana shows logs for all 6 services under index pattern `circleguard-*` |
| US-11 | As a developer, I want distributed tracing via Jaeger so I can trace requests across services | Jaeger UI at `localhost:16686` shows traces from auth-service and gateway-service |
| US-12 | As an operator, I want Grafana dashboards for all 6 services so I can monitor each independently | Dashboard exists per service showing request rate, error rate, JVM heap, active threads |
| US-13 | As a security engineer, I want RBAC applied to all services so access is principle-of-least-privilege | Each service has ServiceAccount + Role + RoleBinding; verified with `kubectl auth can-i` |
| US-14 | As a stakeholder, I want complete project documentation so the system can be operated and evaluated | All docs in `docs/` present; README links to each; demo video recorded |

---

## 4. Definition of Done

A user story is **Done** when:
1. Code is merged to `main` via a Pull Request.
2. All automated tests pass (unit, integration, E2E).
3. Relevant documentation is updated.
4. The acceptance criteria above are verifiably met.
5. The GitHub Projects card is moved to the **Done** column.

---

## 5. Change Management

- **All changes** go through a PR. No hotfixes directly to `main` unless critical.
- PR description must reference the user story number (e.g. `Closes #US-02`).
- Breaking changes require a `BREAKING CHANGE:` footer in the commit message (triggers major version bump).
- Every merge to `main` triggers the Jenkins pipeline, which auto-generates `CHANGELOG.md`.
- Rollback plan: `git revert <merge-commit>` + re-run pipeline. Documented in `docs/OPERATIONS.md`.

---

## 6. Release Tagging

Tags follow **Semantic Versioning** (`vMAJOR.MINOR.PATCH`):

| Commit type | Version bump |
|-------------|-------------|
| `fix:` | PATCH |
| `feat:` | MINOR |
| `BREAKING CHANGE:` | MAJOR |

Current baseline: `v1.0.0` (Workshop 2 delivery).  
Sprint 1 target: `v1.1.0` (IaC + Design Patterns).  
Sprint 2 target: `v1.2.0` (CI/CD + Observability + Security).
