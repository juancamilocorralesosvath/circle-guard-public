# Phase 4 — Manual Setup Guide

The code changes for Phase 4 are already committed. This document covers the one-time manual steps required to make SonarQube, Trivy, and Slack work in the Jenkins pipeline.

---

## 1. SonarQube

### 1a. Start SonarQube

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:lts-community
```

Wait about 60 seconds for it to fully start, then open `http://localhost:9000`.

Default credentials: `admin` / `admin`. You will be forced to change the password on first login — set it to something you'll remember.

### 1b. Generate an analysis token

1. Click your avatar (top right) → **My Account → Security**
2. Under **Generate Tokens**, enter name `jenkins`, type **Global Analysis Token**
3. Click **Generate** and copy the token immediately — you won't see it again

### 1c. Configure Jenkins — SonarQube plugin

First, make sure the **SonarQube Scanner** plugin is installed:
- Manage Jenkins → Plugins → Available → search `SonarQube Scanner` → install

Then configure the server:
1. Manage Jenkins → **Configure System** → scroll to **SonarQube servers**
2. Check **Environment variables** (enables `withSonarQubeEnv`)
3. Click **Add SonarQube**
4. Fill in:
   - Name: `sonarqube` ← must be exactly this, it matches the Jenkinsfile
   - Server URL: `http://localhost:9000`
   - Server authentication token: click **Add** → **Jenkins** → Kind: **Secret text** → Secret: paste the token from step 1b → ID: `sonarqube-token`
5. Select the newly added credential in the dropdown
6. Click **Save**

### 1d. Verify SonarQube is working

After your next pipeline run, open `http://localhost:9000` and confirm the `circleguard` project appears with analysis results. The **Quality Gate** stage in Jenkins should show green.

---

## 2. Trivy — Rebuild the Jenkins agent image

The `jenkins/agent/Dockerfile` now includes Trivy. You need to rebuild and re-push the agent image so Jenkins uses the updated version.

```bash
cd jenkins/agent
docker build -t circleguard-jenkins-agent:latest .
```

Verify Trivy is present:

```bash
docker run --rm circleguard-jenkins-agent:latest trivy --version
# Expected: Version: 0.51.4
```

If your Jenkins agent image is stored in Docker Hub or a registry, push it:

```bash
docker tag circleguard-jenkins-agent:latest juanc0410/circleguard-jenkins-agent:latest
docker push juanc0410/circleguard-jenkins-agent:latest
```

Then restart the Jenkins agent pod so it picks up the new image:

```bash
kubectl rollout restart deployment/jenkins-agent -n jenkins
```

> If your agent is configured as a pod template in Jenkins (not a Deployment), just delete the agent pod and Jenkins will recreate it with the new image.

---

## 3. Slack

### 3a. Create a Slack app and get a token

1. Go to `https://api.slack.com/apps` → **Create New App** → **From scratch**
2. Name: `CircleGuard CI`, workspace: your team workspace
3. In the left menu → **OAuth & Permissions** → under **Bot Token Scopes** add:
   - `chat:write`
   - `chat:write.public`
4. Click **Install to Workspace** → **Allow**
5. Copy the **Bot User OAuth Token** (starts with `xoxb-`)

### 3b. Create the `#ci-alerts` channel in Slack

In your Slack workspace, create a channel named `#ci-alerts` (or use an existing one). Invite the CircleGuard CI app to that channel:

```
/invite @CircleGuardCI
```

### 3c. Configure Jenkins — Slack plugin

First, install the plugin:
- Manage Jenkins → Plugins → Available → search `Slack Notification` → install

Then configure it:
1. Manage Jenkins → **Configure System** → scroll to **Slack**
2. Workspace: your Slack workspace name (e.g. `myteam`)
3. Credential: click **Add** → **Jenkins** → Kind: **Secret text** → Secret: paste the `xoxb-` token → ID: `slack-token`
4. Select `slack-token` in the credential dropdown
5. Default channel: `#ci-alerts`
6. Click **Test Connection** — you should see a test message in `#ci-alerts`
7. Click **Save**

---

## 4. Verification checklist

Run a pipeline build after completing all steps above and confirm:

- [ ] `SonarQube Analysis` stage completes without error
- [ ] `Quality Gate` stage shows green (passed)
- [ ] `circleguard` project visible at `http://localhost:9000`
- [ ] `Container Scan` stage shows Trivy output for each of the 6 service images
- [ ] On a successful build: `#ci-alerts` receives a green SUCCESS message
- [ ] On a failed build: `#ci-alerts` receives a red FAILED message with a link to the build

---

## Troubleshooting

**SonarQube Analysis fails with "connection refused"**
→ SonarQube container is not running. Run `docker start sonarqube` or re-run the `docker run` command from step 1a.

**Quality Gate stays pending forever**
→ The SonarQube webhook is not configured. In SonarQube UI: Administration → Configuration → Webhooks → Create → Name: `jenkins`, URL: `http://<your-jenkins-host>:8080/sonarqube-webhook/`

**Container Scan fails with "trivy: command not found"**
→ The agent image was not rebuilt. Follow step 2 to rebuild and redeploy the agent image.

**Slack test connection fails**
→ Check that the bot was invited to `#ci-alerts` with `/invite @CircleGuardCI`. Also verify the token starts with `xoxb-` (bot token), not `xoxp-` (user token).
