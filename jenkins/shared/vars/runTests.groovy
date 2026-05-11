#!/usr/bin/env groovy
// Run unit or integration tests
def call(String testType='unit') {
  if (testType == 'unit') {
    sh './gradlew test --no-daemon'
  } else if (testType == 'integration') {
    sh './gradlew integrationTest --no-daemon'
  } else {
    sh './gradlew test --no-daemon'
  }
}
return this
