#!/usr/bin/env groovy
// Helper to build a single service with Gradle
def call(String svc) {
  sh "./gradlew :services:circleguard-${svc}:bootJar --no-daemon"
}
return this
