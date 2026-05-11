import jenkins.model.Jenkins
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject

def j = Jenkins.get()
def cloudName = 'kubernetes'
def cloud = j.clouds.find { it.name == cloudName }

if (cloud == null) {
  println "Kubernetes cloud '${cloudName}' not found; ensure Kubernetes plugin and cloud configuration exist."
} else {
  println "Found Kubernetes cloud: ${cloudName}"
  def label = 'docker-builder'
  def existing = cloud.getTemplates().find { it.label == label }
  def yaml = '''apiVersion: v1
kind: Pod
spec:
  dnsPolicy: ClusterFirst
  securityContext:
    supplementalGroups: [0]
  containers:
  - name: jnlp
    image: juanc0410/jenkins-agent:latest
    workingDir: /home/jenkins/agent
    env:
    - name: DOCKER_HOST
      value: unix:///var/run/docker.sock
  - name: docker
    image: docker:24
    command:
    - cat
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run/docker.sock
  volumes:
  - name: docker-sock
    hostPath:
      path: /var/run/docker.sock
      type: Socket
  restartPolicy: Never
'''

  if (existing) {
    existing.setYaml(yaml)
    existing.setName('docker-builder')
    existing.setLabel(label)
    println "Updated existing PodTemplate '${label}' in cloud '${cloudName}'"
  } else {
    def pt = new PodTemplate()
    pt.setName('docker-builder')
    pt.setLabel('docker-builder')
    pt.setYaml(yaml)
    cloud.addTemplate(pt)
    println "Added PodTemplate '${label}' to cloud '${cloudName}'"
  }
  j.save()
}

// Trigger dev branch builds once (marker file prevents repeats)
def marker = new File(Jenkins.get().rootDir, ".dev_builds_triggered")
if (!marker.exists()) {
  println "Triggering 'dev' branch builds for all multibranch projects (if present)"
  Jenkins.get().items.each { item ->
    if (item instanceof WorkflowMultiBranchProject) {
      def branch = item.getItem('dev')
      if (branch) {
        branch.scheduleBuild2(0)
        println "Scheduled build: ${item.fullName} :: dev"
      } else {
        println "No 'dev' branch under ${item.fullName}"
      }
    }
  }
  try {
    marker.createNewFile()
  } catch (Exception e) {
    println "Failed to write marker file: ${e.message}"
  }
} else {
  println "Dev branch builds already triggered previously; skipping."
}
