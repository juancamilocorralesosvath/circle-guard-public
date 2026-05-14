pipeline {
  agent { label 'docker-builder' }
  environment {
    DOCKER_REGISTRY = 'docker.io'
    DOCKER_USER = 'juanc0410'
    SERVICES = 'auth-service identity-service form-service promotion-service gateway-service notification-service'
  }
  options {
    ansiColor('xterm')
    timestamps()
    disableConcurrentBuilds()
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script { env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim() }
      }
    }

    stage('Build & Test') {
      steps {
        sh './gradlew clean build --no-daemon'
        junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
        archiveArtifacts artifacts: 'services/**/build/libs/*.jar', fingerprint: true, allowEmptyArchive: true
      }
      post {
        always {
          publishHTML(target: [
              allowMissing: false,
              alwaysLinkToLastBuild: true,
              keepAll: true,
              reportDir: 'services/circleguard-form-service/build/reports/tests/test',
              reportFiles: 'index.html',
              reportName: 'JUnit Report'
          ])
        }
      }
    }

    stage('Docker Build & Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_HUB_USER', passwordVariable: 'DOCKER_HUB_PASS')]) {
          script {
            sh 'echo "$DOCKER_HUB_PASS" | docker login -u "$DOCKER_HUB_USER" --password-stdin $DOCKER_REGISTRY'
            def buildDate = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
            SERVICES.split().each { svc ->
              def image = "${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}"
              def tag = "${GIT_COMMIT_SHORT}"
              sh """
                docker build \
                  --label "org.opencontainers.image.revision=${GIT_COMMIT_SHORT}" \
                  --label "org.opencontainers.image.created=${buildDate}" \
                  --label "org.opencontainers.image.source=https://github.com/${DOCKER_USER}/circle-guard" \
                  --label "org.opencontainers.image.version=${GIT_COMMIT_SHORT}" \
                  -t ${image}:${tag} \
                  -f services/circleguard-${svc}/Dockerfile .
                docker tag ${image}:${tag} ${image}:latest
                docker push ${image}:${tag}
                docker push ${image}:latest
              """
            }
          }
        }
      }
    }

    stage('Security Scan') {
      steps {
        script {
          def onMain = env.BRANCH_NAME in ['main', 'master']
          def trivyExit = onMain ? 1 : 0

          SERVICES.split().each { svc ->
            def image = "${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}:${GIT_COMMIT_SHORT}"
            def rc = sh(returnStatus: true, script: """
              docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
                aquasec/trivy:latest image \
                --severity HIGH,CRITICAL --exit-code ${trivyExit} --no-progress ${image}
            """)
            if (rc != 0) {
              if (onMain) {
                error "Trivy: HIGH/CRITICAL vulnerabilities in ${image} — blocking deploy."
              } else {
                unstable "Trivy: vulnerabilities found in ${image} — build marked unstable."
              }
            }
          }

          def owaspRc = sh(returnStatus: true, script: './gradlew dependencyCheckAggregate --no-daemon')
          if (owaspRc != 0) {
            if (onMain) {
              error 'OWASP Dependency Check: HIGH/CRITICAL vulnerabilities found.'
            } else {
              unstable 'OWASP Dependency Check: vulnerabilities found — build marked unstable.'
            }
          }
        }
      }
      post {
        always {
          archiveArtifacts artifacts: 'build/reports/dependency-check-report.*', allowEmptyArchive: true
          publishHTML(target: [
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: 'build/reports',
            reportFiles: 'dependency-check-report.html',
            reportName: 'OWASP Dependency Report'
          ])
        }
      }
    }

    stage('Deploy to Dev') {
      when { branch 'dev' }
      steps {
        script { deployToEnv('dev', 'circleguard-dev') }
      }
    }

    stage('Dev Smoke Tests') {
      when { branch 'dev' }
      steps {
        script {
          runSmokeTest('circleguard-dev',
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-auth-service:8180/actuator/health/readiness && ' +
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-gateway-service:8087/actuator/health/readiness')
        }
      }
    }

    stage('Deploy to Stage') {
      when { branch 'staging' }
      steps {
        script { deployToEnv('staging', 'circleguard-staging') }
      }
    }

    stage('Staging Smoke Tests') {
      when { branch 'staging' }
      steps {
        script {
          runSmokeTest('circleguard-staging',
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-auth-service:8180/actuator/health/readiness && ' +
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-gateway-service:8087/actuator/health/readiness && ' +
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-form-service:8086/actuator/health/readiness && ' +
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-promotion-service:8088/actuator/health/readiness')
        }
      }
    }

    stage('E2E Tests') {
      when { branch 'staging' }
      steps {
        withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
          sh '''
            export KUBECONFIG=$KUBECONFIG_FILE
            kubectl create configmap e2e-test-files \
              --from-file=mobile/playwright.config.ts \
              --from-file=mobile/e2e/tests/ \
              -n circleguard-staging --dry-run=client -o yaml | kubectl apply -f -
            kubectl delete job circleguard-e2e-tests -n circleguard-staging --ignore-not-found=true
            kubectl apply -f mobile/e2e/playwright-k8s-job.yaml
            kubectl wait --for=condition=complete job/circleguard-e2e-tests \
              -n circleguard-staging --timeout=600s
          '''
        }
      }
      post {
        always {
          withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
            sh '''
              export KUBECONFIG=$KUBECONFIG_FILE
              mkdir -p mobile/test-results
              POD=$(kubectl get pods -n circleguard-staging -l job-name=circleguard-e2e-tests \
                -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo '')
              if [ -n "$POD" ]; then
                kubectl cp circleguard-staging/$POD:/app/test-results/results.xml \
                  mobile/test-results/e2e-results.xml 2>/dev/null || true
              fi
              kubectl delete job circleguard-e2e-tests -n circleguard-staging --ignore-not-found=true || true
            '''
          }
          junit allowEmptyResults: true, testResults: 'mobile/test-results/e2e-results.xml'
        }
      }
    }

    stage('Performance Tests') {
      when { branch 'staging' }
      steps {
        withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
          sh '''
            export KUBECONFIG=$KUBECONFIG_FILE
            kubectl create configmap locust-scripts-config \
              --from-file=locustfile.py=performance/locustfile.py \
              -n circleguard-staging --dry-run=client -o yaml | kubectl apply -f -
            kubectl delete job locust-perf-test -n circleguard-staging --ignore-not-found=true
            kubectl apply -f performance/locust-k8s-job.yaml
            kubectl wait --for=condition=complete job/locust-perf-test \
              -n circleguard-staging --timeout=900s
          '''
        }
      }
      post {
        always {
          withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
            sh '''
              export KUBECONFIG=$KUBECONFIG_FILE
              mkdir -p performance/results/peak
              POD=$(kubectl get pods -n circleguard-staging -l job-name=locust-perf-test \
                -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo '')
              if [ -n "$POD" ]; then
                kubectl cp circleguard-staging/$POD:/results/report.html \
                  performance/results/peak/report.html 2>/dev/null || true
                kubectl cp circleguard-staging/$POD:/results/stats_stats.csv \
                  performance/results/peak/stats_stats.csv 2>/dev/null || true
                kubectl cp circleguard-staging/$POD:/results/stats_failures.csv \
                  performance/results/peak/stats_failures.csv 2>/dev/null || true
              fi
              kubectl delete job locust-perf-test -n circleguard-staging --ignore-not-found=true || true
            '''
          }
          publishHTML(target: [
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: 'performance/results/peak',
            reportFiles: 'report.html',
            reportName: 'Locust Peak Load Report'
          ])
          archiveArtifacts artifacts: 'performance/results/**/*.csv', allowEmptyArchive: true
        }
      }
    }

    stage('Stress Tests') {
      when { branch 'staging' }
      steps {
        withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
          sh '''
            export KUBECONFIG=$KUBECONFIG_FILE
            kubectl delete job locust-stress-test -n circleguard-staging --ignore-not-found=true
            kubectl apply -f performance/locust-k8s-stress-job.yaml
            kubectl wait --for=condition=complete job/locust-stress-test \
              -n circleguard-staging --timeout=900s || true
          '''
        }
      }
      post {
        always {
          withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
            sh '''
              export KUBECONFIG=$KUBECONFIG_FILE
              mkdir -p performance/results/stress
              POD=$(kubectl get pods -n circleguard-staging -l job-name=locust-stress-test \
                -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo '')
              if [ -n "$POD" ]; then
                kubectl cp circleguard-staging/$POD:/results/stress-report.html \
                  performance/results/stress/report.html 2>/dev/null || true
                kubectl cp circleguard-staging/$POD:/results/stress-stats_stats.csv \
                  performance/results/stress/stress-stats_stats.csv 2>/dev/null || true
                kubectl cp circleguard-staging/$POD:/results/stress-stats_failures.csv \
                  performance/results/stress/stress-stats_failures.csv 2>/dev/null || true
              fi
              kubectl delete job locust-stress-test -n circleguard-staging --ignore-not-found=true || true
            '''
          }
          publishHTML(target: [
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: 'performance/results/stress',
            reportFiles: 'report.html',
            reportName: 'Locust Stress Report'
          ])
        }
      }
    }

    stage('Validate Staging Promotion') {
      when {
        anyOf { branch 'master'; branch 'main' }
        not { changelog '.*\\[skip ci\\].*' }
      }
      steps {
        withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
          sh """
            export KUBECONFIG=\$KUBECONFIG_FILE
            FAILED=0
            for svc in auth-service identity-service form-service promotion-service gateway-service notification-service; do
              IMG=\$(kubectl get deployment/circleguard-\$svc -n circleguard-staging \
                -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo 'unknown')
              if echo "\$IMG" | grep -q '${GIT_COMMIT_SHORT}'; then
                echo "PASS: circleguard-\$svc is on commit ${GIT_COMMIT_SHORT}."
              else
                echo "FAIL: circleguard-\$svc is running '\$IMG', expected commit ${GIT_COMMIT_SHORT}."
                FAILED=1
              fi
            done
            exit \$FAILED
          """
        }
      }
    }

    stage('Approval Gate') {
      when {
        anyOf { branch 'master'; branch 'main' }
        not { changelog '.*\\[skip ci\\].*' }
      }
      steps {
        timeout(time: 30, unit: 'MINUTES') {
          input message: "Deploy commit ${GIT_COMMIT_SHORT} to production?",
                submitter: 'admin,devops-lead,release-manager',
                ok: 'Approve and Deploy'
        }
      }
    }

    stage('Generate Release Notes') {
      when {
        anyOf { branch 'master'; branch 'main' }
        not { changelog '.*\\[skip ci\\].*' }
      }
      steps {
        script {
          env.RELEASE_VERSION = "v${new Date().format('yyyy.MM.dd')}-${env.BUILD_NUMBER}"
          def lastTag = sh(script: 'git describe --tags --abbrev=0 2>/dev/null || echo ""', returnStdout: true).trim()
          def logRange = lastTag ? "${lastTag}..HEAD" : '--max-count=20'
          def releaseNotes = sh(script: "git log ${logRange} --oneline --no-merges", returnStdout: true).trim()
          def since = lastTag ?: 'beginning of project'
          def notes = "# Release ${env.RELEASE_VERSION}\n\n" +
                      "**Date:** ${new Date().format('yyyy-MM-dd HH:mm:ss')}\n" +
                      "**Commit:** ${env.GIT_COMMIT_SHORT}\n" +
                      "**Pipeline:** ${env.BUILD_TAG}\n\n" +
                      "## Changes since ${since}:\n" +
                      "${releaseNotes}\n\n" +
                      "---\nBuilt by Jenkins"
          writeFile file: 'RELEASE_NOTES.md', text: notes
          archiveArtifacts artifacts: 'RELEASE_NOTES.md'
        }
      }
    }

    stage('Deploy to Production') {
      when {
        anyOf { branch 'master'; branch 'main' }
        not { changelog '.*\\[skip ci\\].*' }
      }
      steps {
        script { deployToEnv('prod', 'circleguard-prod') }
      }
    }

    stage('Production Smoke Tests') {
      when {
        anyOf { branch 'master'; branch 'main' }
        not { changelog '.*\\[skip ci\\].*' }
      }
      steps {
        script {
          runSmokeTest('circleguard-prod',
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-auth-service:8180/actuator/health/readiness && ' +
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-gateway-service:8087/actuator/health/readiness && ' +
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-form-service:8086/actuator/health/readiness && ' +
            'curl -f --connect-timeout 5 --max-time 10 http://circleguard-promotion-service:8088/actuator/health/readiness')
        }
      }
    }

    stage('Tag Release') {
      when {
        anyOf { branch 'master'; branch 'main' }
        not { changelog '.*\\[skip ci\\].*' }
      }
      steps {
        script {
          withCredentials([usernamePassword(credentialsId: 'github', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_TOKEN')]) {
            sh """
              git remote set-url origin https://\${GIT_USERNAME}:\${GIT_TOKEN}@github.com/juancamilocorralesosvath/circle-guard-public.git
              git tag -a ${env.RELEASE_VERSION} -m "Release ${env.RELEASE_VERSION} - build ${env.BUILD_NUMBER}, commit ${env.GIT_COMMIT_SHORT}"
              git push origin ${env.RELEASE_VERSION}
            """
          }
          withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_HUB_USER', passwordVariable: 'DOCKER_HUB_PASS')]) {
            sh 'echo "$DOCKER_HUB_PASS" | docker login -u "$DOCKER_HUB_USER" --password-stdin $DOCKER_REGISTRY'
            SERVICES.split().each { svc ->
              def image = "${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}"
              sh """
                docker tag ${image}:${GIT_COMMIT_SHORT} ${image}:${env.RELEASE_VERSION}
                docker push ${image}:${env.RELEASE_VERSION}
              """
            }
          }
        }
      }
    }

    stage('Archive Release Metadata') {
      when {
        anyOf { branch 'master'; branch 'main' }
        not { changelog '.*\\[skip ci\\].*' }
      }
      steps {
        script {
          def lastTag = sh(script: "git describe --tags --abbrev=0 ${env.RELEASE_VERSION}^ 2>/dev/null || echo ''", returnStdout: true).trim()
          def logRange = lastTag ? "${lastTag}..${env.RELEASE_VERSION}" : "${env.RELEASE_VERSION}"
          def changelog = sh(script: "git log ${logRange} --oneline --no-merges", returnStdout: true).trim()
          def today = new Date().format('yyyy-MM-dd')
          def existing = fileExists('CHANGELOG.md') ? readFile('CHANGELOG.md') : ''
          writeFile file: 'CHANGELOG.md', text: "## ${env.RELEASE_VERSION} (${today})\n\n${changelog}\n\n${existing}"
          withCredentials([usernamePassword(credentialsId: 'github', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_TOKEN')]) {
            sh """
              git remote set-url origin https://\${GIT_USERNAME}:\${GIT_TOKEN}@github.com/juancamilocorralesosvath/circle-guard-public.git
              git config user.email "jenkins@ci.internal"
              git config user.name "Jenkins CI"
              git add CHANGELOG.md
              git commit -m "chore: update CHANGELOG for ${env.RELEASE_VERSION} [skip ci]"
              git push origin HEAD
            """
          }
          archiveArtifacts artifacts: 'CHANGELOG.md', allowEmptyArchive: true
        }
      }
    }
  }

  post {
    failure {
      echo 'Pipeline failed - check logs and consider running rollback steps from CI_CD_RUNBOOK.md'
    }
  }
}

def deployToEnv(String overlay, String namespace) {
    echo "Deploying to ${overlay} overlay in namespace ${namespace}"
    withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
        try {
            sh """
                export KUBECONFIG=\$KUBECONFIG_FILE
                kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -
                KUST_TMP=\$(mktemp -d)
                cp -r k8s "\$KUST_TMP/"
                cd "\$KUST_TMP/k8s/overlays/${overlay}"
                ${SERVICES.split().collect { svc -> "kustomize edit set image circleguard-${svc}=${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}:${GIT_COMMIT_SHORT}" }.join("\n                ")}
                kustomize build . | kubectl apply -f -
                rm -rf "\$KUST_TMP"
            """
            sh """
                set -e
                export KUBECONFIG=\$KUBECONFIG_FILE
                ${SERVICES.split().collect { svc -> "kubectl rollout status deployment/circleguard-${svc} -n ${namespace} --timeout=300s" }.join("\n                ")}
            """
            sh """
                set -e
                export KUBECONFIG=\$KUBECONFIG_FILE
                ${SERVICES.split().collect { svc -> "kubectl wait --for=condition=available deployment/circleguard-${svc} -n ${namespace} --timeout=60s" }.join("\n                ")}
            """
            sh """
                export KUBECONFIG=\$KUBECONFIG_FILE
                kubectl run readiness-check-${env.BUILD_NUMBER} \
                  --rm --restart=Never \
                  -n ${namespace} --image=curlimages/curl:8.5.0 --timeout=60s -- \
                  sh -c 'curl -f --connect-timeout 5 --max-time 10 http://circleguard-auth-service:8180/actuator/health/readiness && curl -f --connect-timeout 5 --max-time 10 http://circleguard-gateway-service:8087/actuator/health/readiness && curl -f --connect-timeout 5 --max-time 10 http://circleguard-form-service:8086/actuator/health/readiness && curl -f --connect-timeout 5 --max-time 10 http://circleguard-promotion-service:8088/actuator/health/readiness'
            """
        } catch (Exception e) {
            echo "Deployment to ${overlay} failed: ${e.message}. Initiating rollback..."
            sh """
                export KUBECONFIG=\$KUBECONFIG_FILE
                ROLLBACK_FAILED=0
                for svc in ${SERVICES.split().join(' ')}; do
                  echo "  [ROLLBACK] circleguard-\$svc in ${namespace}..."
                  kubectl rollout undo deployment/circleguard-\$svc -n ${namespace} || true
                  if kubectl rollout status deployment/circleguard-\$svc -n ${namespace} --timeout=120s; then
                    echo "  [ROLLBACK OK] circleguard-\$svc stabilized."
                  else
                    echo "  [ROLLBACK FAIL] circleguard-\$svc did not stabilize — manual intervention required."
                    ROLLBACK_FAILED=1
                  fi
                done
                [ \$ROLLBACK_FAILED -eq 0 ] && echo "All services rolled back." || echo "WARNING: partial rollback failure detected."
            """
            error "Deployment to ${overlay} failed. Rollback attempted — see log for per-service results."
        }
    }
}

def runSmokeTest(String namespace, String endpoints) {
    withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
        sh """
            export KUBECONFIG=\$KUBECONFIG_FILE
            kubectl run smoke-${namespace}-${env.BUILD_NUMBER} \
              --rm --restart=Never \
              -n ${namespace} --image=curlimages/curl:8.5.0 --timeout=120s -- \
              sh -c '${endpoints}'
        """
    }
}
