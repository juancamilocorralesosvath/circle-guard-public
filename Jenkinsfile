pipeline {
  agent none
  environment {
    DOCKER_REGISTRY = 'docker.io'
    DOCKER_USER = 'juanc0410'
    SERVICES = 'auth-service identity-service form-service promotion-service gateway-service notification-service'
  }
  options {
    ansiColor('xterm')
    timestamps()
  }
  stages {
    stage('Checkout') {
      agent { label 'docker-builder' }
      steps {
        checkout scm
        script { env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim() }
      }
    }

    stage('Static Validation') {
      agent { label 'docker-builder' }
      steps {
        sh './gradlew check --no-daemon'
      }
    }

    stage('Unit Tests') {
      agent { label 'docker-builder' }
      steps {
        sh './gradlew test --no-daemon'
        junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
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

    stage('Build') {
      agent { label 'docker-builder' }
      steps {
        sh './gradlew clean build --no-daemon'
        archiveArtifacts artifacts: 'services/**/build/libs/*.jar', fingerprint: true, allowEmptyArchive: true
      }
    }

    stage('Docker Build & Push') {
      agent { label 'docker-builder' }
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
      agent { label 'docker-builder' }
      steps {
        echo "Deploying to dev overlay"
        withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
          sh 'export KUBECONFIG=$KUBECONFIG_FILE'
          script {
            // set images in kustomize overlay (will create images: entries if missing)
            SERVICES.split().each { svc ->
              def image = "${DOCKER_REGISTRY}/${DOCKER_USER}/circleguard-${svc}:${GIT_COMMIT_SHORT}"
              sh "kustomize edit set image circleguard-${svc}=${image} || true"
            }
            sh 'kustomize build k8s/overlays/dev | kubectl apply -f -'
            // rollout validation for each deployment
            SERVICES.split().each { svc ->
              def deploy = "circleguard-${svc}"
              sh "kubectl rollout status deployment/${deploy} -n circleguard-dev --timeout=180s"
              sh "kubectl wait --for=condition=ready pod -l app=${deploy} -n circleguard-dev --timeout=120s || true"
            }
          }
        }
      }
    }

    stage('Smoke Tests') {
      agent { label 'docker-builder' }
      steps {
        echo 'Running lightweight in-cluster smoke checks'
        withCredentials([file(credentialsId: 'kubeconfig-juanc0410', variable: 'KUBECONFIG_FILE')]) {
          sh 'export KUBECONFIG=$KUBECONFIG_FILE'
          sh "kubectl run --rm -i --restart=Never smoke-curl -n circleguard-dev --image=radial/busyboxplus:curl -- sh -c \"curl -sS http://circleguard-auth-service:8180/actuator/health/readiness\""
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
