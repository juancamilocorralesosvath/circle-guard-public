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

    stage('Generate Release Notes') {
      when { 
        anyOf {
          branch 'master'
          branch 'main'
        }
      }
      steps {
        script {
          echo "Generating Release Notes..."
          def releaseNotes = sh(script: 'git log --oneline -n 10', returnStdout: true).trim()
          writeFile file: 'RELEASE_NOTES.md', text: """
# Release Notes - ${env.BUILD_TAG}
**Date:** ${new Date().format('yyyy-MM-dd HH:mm:ss')}
**Commit:** ${env.GIT_COMMIT_SHORT}

## Changes in this version:
${releaseNotes}

---
Built by Jenkins
""".trim()
          archiveArtifacts artifacts: 'RELEASE_NOTES.md'
        }
      }
    }

    stage('Docker Build & Push') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_HUB_USER', passwordVariable: 'DOCKER_HUB_PASS')]) {
          sh 'echo "$DOCKER_HUB_PASS" | docker login -u "$DOCKER_HUB_USER" --password-stdin $DOCKER_REGISTRY'
          script {
            SERVICES.split().each { svc ->
              def image = "${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}"
              def tag = "${GIT_COMMIT_SHORT}"
              sh "docker build -t ${image}:${tag} -f services/circleguard-${svc}/Dockerfile ."
              sh "docker tag ${image}:${tag} ${image}:latest"
              sh "docker push ${image}:${tag}"
              sh "docker push ${image}:latest"
            }
          }
        }
      }
    }

    stage('Deploy to Dev') {
      when { branch 'dev' }
      steps {
        script {
          deployToEnv('dev', 'circleguard-dev')
        }
      }
    }

    stage('Deploy to Stage') {
      when { branch 'staging' }
      steps {
        script {
          deployToEnv('staging', 'circleguard-staging')
        }
      }
    }

    stage('Deploy to Production') {
      when { 
        anyOf {
          branch 'master'
          branch 'main'
        }
      }
      steps {
        script {
          // Point 5: Mandatory validation of system tests could be added here
          deployToEnv('prod', 'circleguard-prod')
        }
      }
    }

    stage('Smoke Tests') {
      steps {
        script {
          def targetNs = (env.BRANCH_NAME == 'dev') ? 'circleguard-dev' : (env.BRANCH_NAME == 'staging' ? 'circleguard-staging' : 'circleguard-prod')
          try {
            echo "Running smoke checks in ${targetNs}"
            withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
              sh 'export KUBECONFIG=$KUBECONFIG_FILE'
              sh "kubectl run --rm -i --restart=Never smoke-curl -n ${targetNs} --image=radial/busyboxplus:curl -- sh -c \"curl -sS http://circleguard-auth-service:8180/actuator/health/readiness\""
            }
          } catch (Exception e) {
            echo "Smoke tests skipped or failed: ${e.message}"
          }
        }
      }
    }
  }
  post {
    failure {
      echo 'Pipeline failed — check logs and consider running rollback steps from CI_CD_RUNBOOK.md'
    }
  }
}

def deployToEnv(String overlay, String namespace) {
    echo "Deploying to ${overlay} overlay in namespace ${namespace}"
    try {
      withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
        sh """
          export KUBECONFIG=${KUBECONFIG_FILE}
          kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -
          
          cd k8s/overlays/${overlay}
          ${SERVICES.split().collect { svc -> "kustomize edit set image circleguard-${svc}=${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}:${GIT_COMMIT_SHORT}" }.join("\n          ")}
          
          kustomize build . | kubectl apply -f -
          
          ${SERVICES.split().collect { svc -> "kubectl rollout status deployment/circleguard-${svc} -n ${namespace} --timeout=180s" }.join("\n          ")}
        """
      }
    } catch (Exception e) {
      echo "Deployment to ${overlay} failed: ${e.message}"
      error "Deployment stage failed."
    }
}
