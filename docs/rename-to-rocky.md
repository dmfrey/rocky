# Renaming client-rag-demo to rocky

The repo-side rename is on the `rename-rocky` branch: GitHub Packages URLs and artifact IDs, the
Gradle root project and `spring.application.name`, every Cloud Foundry app, route and service name
(root `manifest.yml`, `offline/`, `application-cloud.yaml` defaults, `deploy-cf.yaml`), the page
title and the docs. Deliberately **not** renamed: the Java package `com.example.clientragdemo`
(not tied to the repo name) and the local Postgres database `client_rag_demo` (renaming it would
orphan developers' existing volumes).

Order matters. The branch cannot simply be merged first: CI would publish to a repo URL that does
not exist yet, and the deploy would look for services that are not renamed yet.

1. **Rename the GitHub repo** (Settings -> General -> Repository name) to `rocky`. Secrets, the
   `production` environment, run history and the `frontend-latest` release carry over. Locally:
   `git remote set-url origin git@github.com:dmfrey/rocky.git`.
2. **Rename the Cloud Foundry objects in place**, so data and bindings are kept (the running apps keep
   working on their old names until the next restart):

   ```bash
   cf rename client-rag-demo-backend          rocky-backend
   cf rename client-rag-demo-frontend         rocky-frontend
   cf rename client-rag-demo-sharepoint-batch rocky-sharepoint-batch
   for s in db chat embed vision; do cf rename-service client-rag-demo-$s rocky-$s; done
   cf map-route rocky-backend  apps.tas-ndc.kuhn-labs.com --hostname rocky-backend
   cf map-route rocky-frontend apps.tas-ndc.kuhn-labs.com --hostname rocky-frontend
   ```

   `rocky-vision` exists only where vision was enabled. Service keys keep their old names; they are
   unused by the apps.
3. **Fix the env var `cf push` will not touch.** `enable-vision.sh` set it by hand, so the manifest
   does not overwrite it, and it still names the old service:

   ```bash
   cf set-env rocky-backend          APP_GENAI_VISION_SERVICE rocky-vision
   cf set-env rocky-sharepoint-batch APP_GENAI_VISION_SERVICE rocky-vision
   ```
4. **Merge `rename-rocky` and approve the deploy.** It publishes `rocky-backend` /
   `rocky-sharepoint-batch` packages (new artifact IDs: the old versions stay under the old names)
   and pushes the manifest under the new names. Check the first publish succeeds against
   `https://maven.pkg.github.com/dmfrey/rocky`.
5. **Clean up:** `cf unmap-route` / `cf delete-route` the old `client-rag-demo-*` hostnames. The
   frontend's backend URL and the backend's CORS origin follow the new routes automatically, but
   existing browser sessions on the old origin are signed out.
6. **Offline site:** download the jars under their new `rocky-*` names and update its own
   `vars.yml` (jar names, route, service names). If it already has the apps deployed under the old
   names, do step 2 there too, with its names.

Local clones: update the remote (step 1). Renaming your checkout folder also changes the key Claude
Code uses for that project's history and memory, and invalidates any sibling git worktrees.
