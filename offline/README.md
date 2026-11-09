# Offline deployment

Deploys the whole application (backend, frontend, SharePoint/filesystem batch job) to a Cloud
Foundry foundation with **no access to GHCR, npm or Maven Central**: the apps are pushed from
files you carry in, not pulled as images or built onsite. The core application (backend + frontend)
is always deployed; the SharePoint/filesystem **batch job is optional** and off by default.

This folder is self-contained - copy it onsite, drop the jars and the frontend bundle next to it,
fill in `vars.yml`, and run `./deploy.sh`.

| File | What it is |
|---|---|
| `manifest.yml` | The CF manifest for the core application: backend + frontend. Everything that differs between sites is a `((variable))`. |
| `manifest-batch.yml` | The optional batch job (`sharepoint-batch`). Pushed only when you ask for it. |
| `vars.yml.example` | The variables, with comments. Copy to `vars.yml` and edit. |
| `preflight.sh` | Checks vars, files, buildpacks, services and route domains before you push (read-only). |
| `prepare-frontend.sh` | Extracts the frontend bundle and points it at your backend route. |
| `enable-vision.sh` | Optional: binds the `vision-service` model (images / scanned PDFs). `deploy.sh` runs it when `vision-service` is set. |
| `enable-sso.sh` | Optional: binds the Tanzu SSO service (`sso-service`) and switches the backend to single sign-on. `deploy.sh` runs it when `auth-mode` is `oidc`. See [Sign-in](#sign-in). |
| `deploy.sh` | `prepare-frontend.sh` + `preflight.sh` + `cf push`, in one command. `--with-batch` / `--without-batch` / `--dry-run`. |
| `lib.sh` | Helpers the scripts share. |

The scripts need only `bash`, `sed`, `grep`, `awk` and the `cf` CLI (logged in and targeting the
org/space you are deploying to).

## 1. Gather artifacts (on a connected machine)

| Artifact | Where to get it |
|---|---|
| `rocky-backend-<version>.jar` | GitHub Packages (Maven): `com.example:rocky-backend` |
| `rocky-sharepoint-batch-<version>.jar` | *Only if you deploy the batch job.* GitHub Packages (Maven): `com.example:rocky-sharepoint-batch` |
| `frontend-dist.tar.gz` | GitHub Release `frontend-latest` |
| this `offline/` folder | the repo |

**Take the backend jar and the frontend bundle from the same build.** Every state-changing request now needs a CSRF token that
the frontend fetches from the backend's `/api/csrf`: a new backend rejects an old frontend's logins and uploads with
403, and a new frontend cannot call an old backend. Download both at the same time, and redeploy both together.

Packages are SNAPSHOTs, so file names carry a timestamp, for example
`rocky-backend-0.0.1-20261015.171059-2.jar` - put the name you downloaded in `vars.yml`
(`backend-jar`, `batch-jar`); there is no need to rename anything.

## 2. Foundation prerequisites

- **Buildpacks:** a Java buildpack that bundles a **Java 25** JRE, and the staticfile buildpack.
  `cf buildpacks` shows their names (`java-buildpack` and `staticfile-buildpack` in `vars.yml`).
- **PostgreSQL** service instance, **with the pgvector extension available**.
- **Two single-model GenAI services**, one per model, so which model is used is decided by which
  service you create - not by a model name in a manifest:

  ```bash
  cf marketplace -e ai-models                                          # lists the plans
  cf create-service ai-models <chat-model-plan>      <chat-service>    # a CHAT model
  cf create-service ai-models <embedding-model-plan> <embed-service>   # a 768-dimension EMBEDDING model
  ```

  Each binding must expose `credentials.endpoint` with `config_url`, `api_key` and `api_base`
  (the Tanzu `ai-models` offering does). A service on an all-models plan is rejected at startup as
  ambiguous unless `APP_GENAI_CHAT_MODEL` / `APP_GENAI_EMBEDDING_MODEL` name the model.
- **Optional - a vision model**, to read image files (`.png`, `.jpg`, `.gif`) and scanned PDFs: a
  third GenAI service on a plan whose model has the `VISION` capability, named by `vision-service`
  in `vars.yml`. Leave it empty and the feature is simply off (such files fail ingestion with a
  message saying a vision model is needed). See [Reading images and scanned PDFs](#reading-images-and-scanned-pdfs).
- **Only if you deploy the batch job:** a **volume service** (block-storage / NFS-style) holding the
  documents, if `ingestion-source` is `filesystem`.

There are no service keys to create and no credentials to configure: both Java apps read Postgres
and their model endpoint(s) from their service bindings (`VCAP_SERVICES`) themselves.

> **Embedding model dimension.** The pgvector column is `vector(768)`, fixed by the database
> schema. At startup the backend (and the batch job, before it ingests anything) reads the column's
> dimension back from the database, asks the embedding model for a test vector and **refuses to
> start** if the two differ, naming both numbers. A different dimension would need a schema
> migration and a full re-index. The chat model can be changed freely (swap the service).

## 3. Fill in `vars.yml`

```bash
cp vars.yml.example vars.yml
$EDITOR vars.yml
```

| Variable | What to put | Also drives |
|---|---|---|
| `backend-app`, `frontend-app` | App names (`rocky-backend`, `rocky-frontend`). Change them (with the routes) to run a second copy beside the first, e.g. a UI preview | `cf` commands in the scripts |
| `batch-app` | *(batch job only)* batch app name (`rocky-sharepoint-batch`) | `cf run-task` target |
| `backend-route` | Backend hostname (no scheme), on a domain from `cf domains` | the frontend's API URL (`prepare-frontend.sh`) |
| `frontend-route` | Frontend hostname | the backend's CORS origin (`https://<frontend-route>`) |
| `backend-jar` | The downloaded backend jar file name | |
| `batch-jar` | *(batch job only)* the downloaded batch jar file name | |
| `frontend-dir` | Where the frontend bundle is extracted (`./frontend-dist`) | |
| `java-buildpack`, `staticfile-buildpack` | Buildpack names from `cf buildpacks` | |
| `deploy-batch` | `true` to deploy the optional batch job, `false` (the default) to leave it out | `./deploy.sh --with-batch` / `--without-batch` override it |
| `db-service` | PostgreSQL service instance name | the binding on the Java apps |
| `chat-service` | Chat GenAI service name | the binding **and** the backend's lookup of it |
| `vision-service` | *Optional* vision-capable GenAI service for images / scanned PDFs; empty = off | bound by `enable-vision.sh` after the push |
| `embed-service` | Embedding GenAI service name | the binding **and** the lookup, in the backend and the batch job |
| `volume-service` | *(batch job, filesystem source only)* volume service name | the binding **and** the batch app's mount-path lookup |
| `ingestion-source` | *(batch job only)* `filesystem` or `graph` | |
| `chunk-size` | Document chunk size in tokens, shared by the backend and the batch job (`128` suits an embedding model with a 512-token limit; lower it if documents fail to embed with a "too many tokens" error) | |
| `backend-memory` | Backend memory (`1500M`). Raise it (`2500M` or more) before uploading documents near the 100 MB limit - see [Document uploads](#document-uploads) | |
| `backend-disk` | Backend disk quota (`2G`): where Tomcat spills an upload while it arrives, on top of the jar | |
| `batch-memory` | *(batch job only)* batch memory (`1500M`) | |
| `stack` | *Optional* - when the foundation has several stacks (`cf stacks`); passed as `--stack` | |
| `chat-timeout` | *Optional* - e.g. `300s`, for slow chat models; `deploy.sh` sets `APP_GENAI_CHAT_TIMEOUT` and restarts the backend. Unset keeps the library default | |
| `retrieval-top-k` | *Optional* - how many document chunks the chat model is given per question (default `4`); `deploy.sh` sets `APP_CHAT_RETRIEVAL_TOP_K` and restarts the backend. See [Tuning for the chat model](#tuning-for-the-chat-model) | |
| `auth-mode` | *Optional* - `jdbc` (default, local accounts) or `oidc` (Tanzu SSO); see [Sign-in](#sign-in) | |
| `sso-service` | *(auth-mode oidc)* the `p-identity` service instance to bind | the binding **and** the backend's lookup of it |
| `sso-bind-params` | *Optional (oidc)* - the full JSON of bind parameters, replacing the default redirect URIs | |
| `registration-enabled` | *Optional (jdbc)* - `false` removes the self-registration endpoint; default `true` | |
| `startup-timeout` | Seconds CF waits for the backend to become healthy (`180`; a JVM start with database migrations is slow) | |

### Sign-in

`auth-mode` in `vars.yml` picks how people sign in - exactly one of:

- **`jdbc`** (the default): local accounts in Rocky's own database. The sign-in page has a registration form. **Anyone who can
  reach the app can create an account**, and there is no other way to add one, so set `registration-enabled: false`
  once the accounts you need exist (the registration endpoint is then absent, not merely refusing).
- **`oidc`**: single sign-on with the **Tanzu SSO tile**. Create the service once
  (`cf create-service p-identity <plan> rocky-sso` - the plans are in `cf marketplace -e p-identity`; the identity
  provider behind it, LDAP or Active Directory, is the tile's concern) and set `auth-mode: oidc` and `sso-service`.
  `deploy.sh` then runs `enable-sso.sh`, which binds the service to the backend, selects oidc mode and restarts it.
  Any user the identity provider signs in may use Rocky - there are no roles. There are no local accounts: no
  registration, no password form, no password change. A user's first sign-in creates their profile row.

OIDC is not specific to Cloud Foundry. Anywhere else (Kubernetes, a laptop) set `APP_AUTH_MODE=oidc` plus
`APP_AUTH_OIDC_ISSUER_URI`, `APP_AUTH_OIDC_CLIENT_ID` and `APP_AUTH_OIDC_CLIENT_SECRET` for any OpenID Connect compliant
provider, and `APP_AUTH_OIDC_USERNAME_ATTRIBUTE` if its username claim is not UAA's `user_name` (`preferred_username` is
common). Register `https://<backend>/login/oauth2/code/sso` with the provider as the redirect URI. On Cloud Foundry the
bound SSO service supplies all of it instead; the plain settings, if given, override the binding one by one.

What `enable-sso.sh` does, and what to check if it does not work:

- **Redirect URIs are registered at bind time.** It passes `https://<backend-route>/login/oauth2/code/sso` (where the identity
  provider sends the browser back) and `https://<frontend-route>/` (where it lands afterwards) as the binding's
  `redirect_uri`. **That parameter name is not verified against a tile** - if the bind fails or sign-in ends with a
  redirect-URI error, check the tile's documentation and give the whole parameter object in `sso-bind-params`.
- **The client credentials come from the binding** (`client_id`, `client_secret`, `auth_domain`), never from `vars.yml` or the
  manifest. The issuer is taken as `<auth_domain>/oauth/token` (UAA's); `APP_AUTH_OIDC_ISSUER_URI` overrides it. The
  username claim is `user_name`, which is what chats and documents are owned by.
- **A username that already belongs to a local account is refused** at SSO sign-in rather than linked (linking would let
  whoever holds that name at the identity provider take the local account over). This only matters when switching a
  deployment from `jdbc` to `oidc`: sign-in fails for those users until the local account is removed.
- **Sign-out** ends the identity provider's session too when it publishes an end-session endpoint (UAA does); the
  post-logout redirect has to be one of the binding's redirect URIs, which is why the frontend URL is included.
- **A failed sign-in** returns the browser to the frontend with a message, and the real reason is in the backend log
  (`SSO sign-in failed: ...`).
- To go back to local accounts: `cf set-env <backend-app> APP_AUTH_MODE jdbc && cf restart <backend-app>` (or set
  `auth-mode: jdbc` and run `deploy.sh`). A user created through SSO has no password and cannot sign in locally.

Both modes protect state-changing requests against cross-site request forgery with a token the frontend fetches from
`/api/csrf` and sends in a header; this is built in and needs no setting.

### Tuning for the chat model

Answer quality depends on how much source text the chat model is shown, not only on which model it is. Each question
retrieves `retrieval-top-k` chunks, and each chunk is at most `chunk-size` tokens, so the grounding is roughly
`top-k x chunk-size` tokens. The defaults (`4` x `128`) give the model only ~500 tokens of source text, which starves
even a large model. The whole prompt must still fit the model's context window:

```
prompt ~= top-k x chunk-size  +  chat history (up to 20 messages)  +  system prompt  +  the answer
```

| Chat model | `retrieval-top-k` | Needs a context window of |
|---|---|---|
| small (e.g. a 2B) | `4` (default) | 4k is enough |
| Gemma 26B | `8` - `12` | **at least 8k**, 16k preferred |
| GPT-OSS-120b | `10` - `16` | 16k or more |

- **Context window is set on the serving side, not in Rocky** (the GenAI tile / the model server). If it is smaller than
  the prompt above, the model truncates or rejects it: raise the window before raising `retrieval-top-k`.
- **But bigger is not better.** When the tile serves models through Ollama, its *context length* is `num_ctx`, and Rocky
  reaches Ollama through its OpenAI-compatible `/v1` routes, which cannot set it per request - the tile setting (or a
  Modelfile `PARAMETER num_ctx`) is the only lever. Ollama reserves KV-cache memory for the *whole* context length when
  the model loads, per parallel slot. A very large value (e.g. the tile's `131072` with an `f16` cache) can leave no GPU
  room for the weights, and Ollama then offloads layers to the CPU: answers become very slow and `chat-timeout` is the
  first symptom. Rocky needs about 8k-16k at `retrieval-top-k` 10.
  - Check with `ollama ps` while the model is loaded: anything other than `100% GPU` is spilling to the CPU.
  - If it is, lower the context length to 16384 (32768 at most).
  - Set the KV cache type to `q8_0` instead of `f16`: it needs flash attention (already on) and roughly halves the cache.
  - Keep parallel requests low (`OLLAMA_NUM_PARALLEL` 1-2): the cache is reserved for each slot.
- `chunk-size` is capped by the embedding model (128 for a 512-token embedder). Changing it only affects documents
  ingested afterwards - re-ingest them to benefit.
- Larger models are slower: set `chat-timeout` (e.g. `300s`) before switching to GPT-OSS-120b or a non-quantized model.
- If the chat service's plan grants several models, name one with `APP_GENAI_CHAT_MODEL`; the backend refuses to start
  rather than guess.

### Document uploads

The backend accepts document uploads up to **100 MB** per file. On Cloud Foundry that has three consequences, none of them in the application's own config:

- **Memory.** Ingestion reads the whole file into the heap and Tika/PDFBox then parses it, so one 100 MB upload needs several hundred MB of heap at once, and a second concurrent one the same again. The default `backend-memory: 1500M` suits ordinary documents; raise it before uploading files near the limit.
- **Disk.** Tomcat spills each part past 2 MB to the JVM temp directory, which is the container's writable layer and counts against `backend-disk`. Set `SPRING_SERVLET_MULTIPART_LOCATION` only if you mount somewhere else.
- **The path in front.** Anything between the client and GoRouter - a load balancer, a WAF, a corporate proxy - may have its own request-body size limit or timeout. GoRouter sets no body limit itself; its request timeout is the foundation's (ask the platform operator). **This is untested against a real foundation.** An upload over the limit is answered `413`.

**Upgrading an existing deployment:** deploy the backend jar and the frontend bundle together (see above). The new settings (`auth-mode`, `sso-service`, `sso-bind-params`, `registration-enabled`, `retrieval-top-k`) are all optional, so an existing `vars.yml` keeps working and sign-in stays local accounts.

**Upgrading an existing `vars.yml`:** add `backend-disk: 2G`, and `backend-app: rocky-backend` and `frontend-app: rocky-frontend` (and `batch-app: rocky-sharepoint-batch` if you deploy the batch job); `preflight.sh` names any variable that is missing.

**A second copy** (e.g. a UI preview): keep one file per site, such as `vars.trevorputbrese.yml.example` (copy to `vars.<name>.yml`, gitignored), and run `VARS=vars.<name>.yml ./deploy.sh frontend-dist.tar.gz`. Use a different `frontend-dir` too. Sharing `db-service` shares users and documents.

Values that must agree are driven by **one** variable each (the route and the CORS origin; a
service's name and the app's lookup of it), so they cannot drift apart.

The volume's mount path needs no setting: CF assigns it per binding, and the batch app reads it
from its own `VCAP_SERVICES`.

### SharePoint (Microsoft Graph) instead of a volume

Set `ingestion-source: graph`, then uncomment the five `APP_SHAREPOINT_*` lines in the batch app's
`env:` in `manifest-batch.yml` and add the matching `sharepoint-*` variables to `vars.yml`. The client
secret then lives in `vars.yml` - keep it out of version control. (Not yet verified against a real
tenant.)

## 4. Deploy

```bash
./deploy.sh frontend-dist.tar.gz     # first time: extracts and prepares the frontend, checks, pushes
./deploy.sh                          # later: re-check and push
```

Or step by step: `./prepare-frontend.sh frontend-dist.tar.gz`, then `./preflight.sh`, then
`cf push -f manifest.yml --vars-file vars.yml`.

`preflight.sh` stops you with a clear `FAIL` line for: a variable with no value or a leftover
`example.com`; a missing jar; a frontend still carrying the placeholder; a buildpack, service or
route domain that does not exist on the foundation you are targeting. It only reads from CF.
If a different `cf` CLI version prints something the script misreads and it fails a check that is
actually fine, `SKIP_FOUNDATION=1 ./preflight.sh` (or `SKIP_FOUNDATION=1 ./deploy.sh`) skips just the
checks that read from CF; the variable and file checks still run.

### The optional batch job

The batch job is **not deployed by default**. Core-only sites need none of its jar, its variables
or its volume service, and `preflight.sh` does not check for them. To include it:

```bash
./deploy.sh --with-batch              # just this once, or
# deploy-batch: true in vars.yml        # every time
```

`--without-batch` overrides `deploy-batch: true`; `--dry-run` does everything except the `cf push`
and prints what would be pushed. Adding the job to an already-deployed site later is the same
command: `./deploy.sh --with-batch` re-pushes the core application (harmlessly) and then the job.

The core application is pushed first and the batch job only afterwards, so the backend has started
and run the database migrations before the job exists: `sharepoint-batch` must never run against a
database the backend has not migrated at least once. Once deployed, trigger ingestion on demand or
from an external scheduler:

```bash
cf run-task <batch-app>
```

### Changing something later

Edit `vars.yml` and run `./deploy.sh`. To change `backend-route`, pass the tarball again
(`./deploy.sh frontend-dist.tar.gz`): an already-prepared bundle has lost its placeholder.

## Troubleshooting

- **Variable errors from `cf push`** ("Expected to find variables: ...") - a variable is missing
  from `vars.yml`; `preflight.sh` lists them first.
- **Staging fails or is OOM-killed** (exit 137) on some cells - retry, or raise staging memory.
- **Staging tries to download a JRE** - the buildpack does not bundle Java 25; use one that does,
  or point it at an internal mirror.
- **Both Java apps set `JBP_CONFIG_JAVA_CF_ENV: '{enabled: false}'`** - keep it. The buildpack
  would otherwise inject a second copy of `java-cfenv` next to the one bundled in each jar.
- **A startup failure that names a GenAI service** says what to fix:
  - "No bound service named '...'" - the service is not in the manifest's `services:` or is named
    differently (check `chat-service` / `embed-service`).
  - "grants N CHAT/EMBEDDING models" - the service is on an all-models plan; bind a single-model
    plan, or name the model with `APP_GENAI_CHAT_MODEL` / `APP_GENAI_EMBEDDING_MODEL`.
  - "returns N-dimension vectors but vector_store.embedding is vector(768)" - the embedding
    service grants a model of a different dimension.
- **Image / scanned PDF uploads end `FAILED`:** the message says why - "need a vision model, and none
  is configured" (`vision-service` not set, or `./enable-vision.sh` not run), "grants no VISION
  model" (the service's model cannot see - pick another plan), "limited to N pages" (raise
  `APP_DOCUMENTS_INGESTION_VISION_MAX_PAGES` or split the file). See
  [Reading images and scanned PDFs](#reading-images-and-scanned-pdfs).
- **`NoClassDefFoundError` for a Redis class** - nothing in these jars uses Redis (checked: no
  Redis library and no reference to `RedisConnectionFactory`). Look at the buildpack's
  Spring auto-reconfiguration (`JBP_CONFIG_SPRING_AUTO_RECONFIGURATION: '{enabled: false}'`) and at
  any Redis service bound to the app.

## Reading images and scanned PDFs

**This is optional, and the deployment is complete without it.** If the foundation has no
vision-capable model, leave `vision-service` empty and skip this section: nothing is bound,
nothing extra is needed in the carry-in folder, and the only difference is that image files and
scanned PDFs fail ingestion (status `FAILED`, with a message saying a vision model is needed)
instead of being read. Text, Word and text-layer PDFs are unaffected.

Text files, Word (`.doc`, `.docx`) and PDFs with a text layer need nothing extra. A **scanned PDF**
(pages that are pictures) or an **image file** has no text for the parser to find, so reading it
needs a vision-capable model:

```bash
cf create-service ai-models <vision-model-plan> <vision-service>   # a model with the VISION capability
```

Then put the service name in `vars.yml` as `vision-service` and run `./deploy.sh` (or
`./enable-vision.sh` on an already-deployed system). It binds the service to the backend (and the
batch job, if deployed), sets `APP_GENAI_VISION_SERVICE` and restarts the backend; `cf push` keeps
both, so later deploys do not undo it. With `vision-service` empty none of this happens.

- Images are sent to the model directly; a PDF that yields no text is rendered page by page (150 dpi)
  and each page is sent. One model call per page, so a scan is much slower than a text PDF, and
  PDFs over 100 pages fail with a clear message (`APP_DOCUMENTS_INGESTION_VISION_MAX_PAGES`).
- The result is the model's transcription, not exact OCR - check tables and small print on a
  sample of your own documents before relying on it.
- Only a PDF with **no** text at all goes through the model. A PDF mixing text pages and scanned
  pages is indexed from its text pages only.
- `cf marketplace` does not show model capabilities, so you only find out whether a plan's model can
  see by using it. It is safe to try: a service with no `VISION` model does **not** stop the
  backend - it starts normally, logs a warning, and image / scanned-PDF uploads fail with
  "Vision service '...' grants no VISION model" in the document's error. Unbind it (below) if so.
- The frontend lists image types in its file picker whether or not vision is on; without it those
  uploads simply end `FAILED` with the message above.
- The service must be on a plan whose model lists `VISION`; a service with several such models needs
  `APP_GENAI_VISION_MODEL` to name one (startup fails otherwise, like the chat and embedding services).
- To turn it off: `cf unset-env <app> APP_GENAI_VISION_SERVICE` and `cf unbind-service <app> <service>`
  for the backend and the batch job, then restart the backend, and clear `vision-service` in `vars.yml`.
