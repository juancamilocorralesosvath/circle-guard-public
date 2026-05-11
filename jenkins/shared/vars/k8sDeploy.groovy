#!/usr/bin/env groovy
// Helper to set images via kustomize and deploy an overlay
def call(String env, Map imagesMap) {
  imagesMap.each { svc, fullImage ->
    sh "kustomize edit set image ${svc}=${fullImage} || true"
  }
  sh "kustomize build k8s/overlays/${env} | kubectl apply -f -"
}
return this
