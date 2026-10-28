# Deploy the Clarity preview

The preview uses `rocky-clarity-backend` and `rocky-clarity-frontend`, leaving the original
apps available. It needs two free routes and 1564 MB of additional app memory. Reusing the
original database shares accounts, documents, and conversations; the preview is not a data
sandbox. Use a separate PostgreSQL service with pgvector when data isolation is required.

## Build on the connected machine

Use Node 26 with npm and Java 25. From the repository root:

```bash
mkdir -p build/clarity/release
cd frontend
npm ci
npm test
npm run lint
VITE_API_BASE_URL=__RUNTIME_API_BASE_URL__ npm run build
tar -czf ../build/clarity/release/frontend-dist.tar.gz -C dist .
cd ..
./gradlew :backend:test --tests com.example.clientragdemo.configuration.GenaiModelConfigurationTest :backend:bootJar
cp backend/build/libs/backend-0.0.1-SNAPSHOT.jar build/clarity/release/rocky-backend.jar
git rev-parse HEAD > build/clarity/release/SOURCE-COMMIT.txt
(cd build/clarity/release && shasum -a 256 rocky-backend.jar frontend-dist.tar.gz > SHA256SUMS)
```

Use a committed source revision for the release. Transfer the repository and the contents of
`build/clarity/release` together, preserving that layout. Keep this archive separate from the
previous working deployment. The CSS includes its fonts; icons are inline SVGs, and
`CLARITY-LICENSE.txt` travels in the frontend bundle. No CDN or package installation is needed
on the restricted side.

## Push from the restricted machine

1. Verify the transferred files with `sha256sum -c SHA256SUMS` inside the release directory.
2. Confirm `cf target`, services, free route/memory quota, and cached Java 25/staticfile
   buildpacks for the selected stack. All three bound services must be ready.
3. Copy `offline/vars.preview.yml.example` to `offline/vars.preview.yml`. Fill in your routes,
   service names, buildpacks, stack, and paths. `chat-timeout: 300s` preserves support for slow
   internal chat models. Both routes must use HTTPS for cross-origin session cookies.
4. Use an empty `frontend-dir` for a new bundle so obsolete hashed assets are not retained.
   From the repository root, prepare the extraction and push:

```bash
VARS=vars.preview.yml ./offline/prepare-frontend.sh ../build/clarity/release/frontend-dist.tar.gz
cf push -f offline/manifest-preview.yml --vars-file offline/vars.preview.yml
```

The frontend preparation substitutes the backend route into a copy of the bundle. Always
keep the original archive; extract it again when changing sites. The manifest sets the backend
CORS origin to the matching frontend route.

## Validate and roll back

Open the preview frontend route from a browser with access to the lab. Check consent, login,
document upload and processing, chat and citations, settings, and chat history actions.
Inspect light/dark appearance and a narrow browser window. Block public network access in a
test browser and confirm its asset requests stay on the app origin; internal API calls must
remain reachable. A font or icon should never require Internet access.

To roll back the visual preview, use the original frontend route; it has not been replaced.
Stop the preview apps when they are no longer needed:

```bash
cf stop rocky-clarity-frontend
cf stop rocky-clarity-backend
```

Stopping releases app memory but retains routes and service bindings. Do not delete shared
services when cleaning up the preview. The underlying model latency is unaffected by styling;
the UI shows a waiting message while an answer is being prepared.
