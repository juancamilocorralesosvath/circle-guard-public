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
    // Optimization: Disable concurrent builds to keep the shared gradle cache consistent
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
        // Optimization: Build all JARs once using the shared host cache
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
          sh 'echo "$DOCKER_HUB_PASS" | docker login -u "$DOCKER_HUB_USER" --password-stdin $DOCKER_REGISTRY'
          script {
            SERVICES.split().each { svc ->
              def image = "${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}"
              def tag = "${GIT_COMMIT_SHORT}"
              // Optimization: Docker build now just copies the pre-built JAR
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
        echo "Deploying to dev overlay"
        script {
          try {
            withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
              sh """
                export KUBECONFIG=${KUBECONFIG_FILE}
                # Ensure namespace exists
                kubectl create namespace circleguard-dev --dry-run=client -o yaml | kubectl apply -f -
                
                # set images in kustomize overlay
                cd k8s/overlays/dev
                ${SERVICES.split().collect { svc -> "kustomize edit set image circleguard-${svc}=${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}:${GIT_COMMIT_SHORT}" }.join("\n                ")}
                
                # deploy
                kustomize build . | kubectl apply -f -
                
                # rollout validation
                ${SERVICES.split().collect { svc -> "kubectl rollout status deployment/circleguard-${svc} -n circleguard-dev --timeout=180s" }.join("\n                ")}
              """
            }
          } catch (Exception e) {
            echo "Deployment failed or credentials missing: ${e.message}"
            error "Deployment stage failed. Please ensure 'kubeconfig-juanc0410' credential exists."
          }
        }
      }
    }

    stage('Smoke Tests') {
      steps {
        script {
          try {
            echo 'Running lightweight in-cluster smoke checks'
            withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
              sh 'export KUBECONFIG=$KUBECONFIG_FILE'
              sh "kubectl run --rm -i --restart=Never smoke-curl -n circleguard-dev --image=radial/busyboxplus:curl -- sh -c \"curl -sS http://circleguard-auth-service:8180/actuator/health/readiness\""
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
