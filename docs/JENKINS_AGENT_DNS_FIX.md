JENKINS AGENT DNS FIX

Summary
- Problem: Dynamically provisioned Jenkins agent pods intermittently fail to resolve `repo.maven.apache.org` and `github.com`.
- Root cause: CoreDNS instability in Docker Desktop environments and excessive DNS search suffix overhead (ndots: 5).

What I changed
1) Hardened `jenkins/agent/Dockerfile`
- Installed networking tools for diagnostics.

2) Updated `jenkins/podtemplates/docker-builder-pod-template.yaml`
- Added `dnsConfig` to explicitly use Google (8.8.8.8) and Cloudflare (1.1.1.1) as fallback nameservers.
- Set `ndots: 1` to prevent unnecessary DNS search suffix iterations for external domains.
- Mounted a host-based Gradle cache (`/Users/vania/.gradle`) to the agent's `/home/jenkins/.gradle` to minimize external dependency downloads and reduce DNS traffic.

Updated Pod Template YAML

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins/label: docker-builder
spec:
  serviceAccountName: jenkins
  dnsPolicy: ClusterFirst
  dnsConfig:
    nameservers:
      - 8.8.8.8
      - 1.1.1.1
    options:
      - name: ndots
        value: "1"
  securityContext:
    supplementalGroups: [0]
  containers:
    - name: jnlp
      image: juanc0410/jenkins-agent:latest
      imagePullPolicy: Always
      workingDir: /home/jenkins/agent
      env:
        - name: DOCKER_HOST
          value: unix:///var/run/docker.sock
      volumeMounts:
        - name: gradle-cache
          mountPath: /home/jenkins/.gradle
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
    - name: gradle-cache
      hostPath:
        path: /Users/vania/.gradle
        type: Directory
  restartPolicy: Never
```

Next Steps for User
1. Update the Pod Template in the Jenkins UI (Manage Jenkins -> Nodes and Clouds -> Clouds -> Kubernetes -> Pod Templates).
2. Ensure the `imagePullPolicy` is set to `Always`.
3. Re-run the build.
