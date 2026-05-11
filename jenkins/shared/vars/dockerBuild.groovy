#!/usr/bin/env groovy
// Helper to build and push docker image for a service
def call(String svc, String registry = 'docker.io', String user = 'juanc0410', String tag = '') {
  def image = "${registry}/${user}/circleguard-${svc}"
  def t = tag ?: sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
  sh "docker build -t ${image}:${t} -f services/circleguard-${svc}/Dockerfile ."
  sh "docker tag ${image}:${t} ${image}:latest"
  sh "docker push ${image}:${t}"
  sh "docker push ${image}:latest"
}
return this
