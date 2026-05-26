# IngeSoft V Final Project
## Description
For this final project, you must implement a complete microservices architecture
using modern DevOps, security, and observability practices. You will work with the
code available at this codebase.
Implement all the microservices of the architecture and integrate them into a Kubernetes environment. (remember we have already implement 6 microservices, you can see which and how in the docs folder, for that read the .md files)
## Main Requirements
- Agile Methodology and Branching Strategy (10%)
- Implement an agile methodology (Scrum or Kanban) for project development
- Define and document a branching strategy (GitFlow, GitHub Flow, or similar)
- Use an agile project management system (Jira, Trello, GitHub Projects, etc.)
- Document sprints, user stories, and acceptance criteria
- Complete at least 2 full iterations during development
- Infrastructure as Code with Terraform (20%)
- Configure all necessary infrastructure using Terraform
- Implement a modular structure
- Implement configuration for multiple environments (dev, stage, prod)
- Document the infrastructure architecture with diagrams
- Implement a remote backend for Terraform state
- Design Patterns (10%)
- Identify and document the design patterns used in the existing architecture
- Implement or improve at least three additional patterns:
- A resilience pattern (Circuit Breaker, Bulkhead, etc.)
- A configuration pattern (External Configuration, Feature Toggle, etc.)
- Document the implemented patterns, their purpose, and benefits
- Advanced CI/CD (15%)
- Implement complete CI/CD pipelines (Jenkins, GitHub Actions, or Azure
DevOps)
- Set up separate environments (dev, stage, prod) with controlled deployment

- Implement SonarQube for static code analysis
- Implement Trivy for container vulnerability scanning
- Implement automatic semantic versioning
- Configure automatic notifications for pipeline failures
- Implement approvals for production deployments
## 5. Comprehensive Testing (15%)
- Implement unit tests for microservices
- Implement integration tests between related services
- Implement E2E tests for complete user flows
- Implement performance and stress tests with Locust
- Implement security testing (OWASP ZAP or similar)
- Generate test coverage and quality reports
- Configure automated execution in pipelines
- Change Management and Release Notes (5%)
- Define a formal Change Management process
- Implement automatic generation of release notes
- Document rollback plans
- Implement a release tagging system
- Observability and Monitoring (10%)
- Implement a monitoring stack with Prometheus and Grafana
- Configure the ELK Stack (Elasticsearch, Logstash, Kibana) for log management
- Implement relevant dashboards for each service
- Configure alerts for critical situations
- Implement distributed tracing (Jaeger, Zipkin, etc.)
- Configure health checks and readiness/liveness probes
- Implement business metrics in addition to technical metrics
## 8. Security (5%)
- Implement continuous vulnerability scanning
- Implement secure secret management
- Configure RBAC for resource access
- Implement TLS for publicly exposed services
## 9. Documentation and presentation (10%)
- Complete project documentation
- Organized Git repository
- Infrastructure costs
- Basic operations manual
- Demonstration video of the system in action
- Project presentation
## Deliverables:
- Complete source code in a Git repository.

- Complete project documentation, including:
- Detailed architecture with diagrams
- Description of the agile methodology implemented
- Design pattern documentation
- Operation and maintenance guides
- Analysis of test results
- Infrastructure-as-code documentation
- Release notes for each version.

- Presentation and demonstration (20–30 minutes) including:
- Architecture and infrastructure
- CI/CD demonstration
- Demonstration of the application in operation
- Monitoring dashboards
- Performance test results
- Lessons learned and recommendations