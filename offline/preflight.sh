#!/usr/bin/env bash
# Checks everything that commonly goes wrong BEFORE you cf push, so you find out in seconds with a
# clear message instead of midway through staging:
#   - vars.yml has a value for every variable the manifests use (and no leftover placeholders)
#   - the jar(s) and the (prepared) frontend bundle exist
# The optional batch job (manifest-batch.yml) is only checked when it is being deployed
# (deploy-batch in vars.yml, or DEPLOY_BATCH=true / ./deploy.sh --with-batch).
#   - the buildpacks, services and route domains exist on the foundation you are targeting
# Read-only: it only runs `cf target`, `cf buildpacks`, `cf service` and `cf domains`.
#
#   ./preflight.sh              uses ./vars.yml
#   VARS=other.yml ./preflight.sh
#   SKIP_FOUNDATION=1 ./preflight.sh   skip the checks that read from CF (buildpacks, services,
#                                      domains) - an escape hatch in case a different cf CLI version
#                                      prints something this script misreads; vars and files are
#                                      still checked
set -u
cd "$(dirname "$0")" || exit 1
. ./lib.sh

fails=0
warns=0
ok()   { printf '  ok    %s\n' "$*"; }
warn() { printf '  WARN  %s\n' "$*"; warns=$((warns + 1)); }
fail() { printf '  FAIL  %s\n' "$*"; fails=$((fails + 1)); }

batch=$(want_batch)
manifests="$MANIFEST"
[ "$batch" = true ] && manifests="$MANIFEST $BATCH_MANIFEST"

echo "== vars ($VARS)"
if [ "$batch" = true ]; then echo "  info  batch job: included"; else echo "  info  batch job: not deployed"; fi
if [ ! -f "$VARS" ]; then
  fail "$VARS not found - copy vars.yml.example to vars.yml and edit it"
else
  # shellcheck disable=SC2086  # $manifests is a deliberate word list
  for v in $(manifest_vars $manifests); do
    val=$(get_var "$v")
    case "$val" in
      "")                  fail "$v has no value in $VARS" ;;
      *example.com*|*'<'*) fail "$v still has a placeholder value: $val" ;;
    esac
  done
  [ "$fails" -eq 0 ] && ok "every variable the manifests use has a value"
fi

echo "== files"
backend_jar=$(get_var backend-jar); frontend_dir=$(get_var frontend-dir)
if [ -f "$backend_jar" ]; then ok "backend jar $backend_jar"; else fail "backend jar not found: ${backend_jar:-<unset>}"; fi
if [ "$batch" = true ]; then
  batch_jar=$(get_var batch-jar)
  if [ -f "$batch_jar" ]; then ok "batch jar $batch_jar"; else fail "batch jar not found: ${batch_jar:-<unset>}"; fi
fi
if [ -d "$frontend_dir" ]; then
  if grep -rq '__RUNTIME_API_BASE_URL__' "$frontend_dir" 2>/dev/null; then
    fail "$frontend_dir still has the backend placeholder - run ./prepare-frontend.sh"
  else
    ok "frontend bundle $frontend_dir (backend URL set)"
  fi
else
  fail "frontend bundle directory not found: ${frontend_dir:-<unset>} - run ./prepare-frontend.sh frontend-dist.tar.gz"
fi

echo "== foundation"
if [ -n "${SKIP_FOUNDATION:-}" ]; then
  warn "foundation checks skipped (SKIP_FOUNDATION is set) - buildpacks, services and routes are NOT verified"
elif ! command -v cf >/dev/null 2>&1; then
  fail "the cf CLI is not installed"
elif ! target=$(cf target 2>&1); then
  fail "not logged in / no org+space targeted - run cf login (and cf target -o ORG -s SPACE)"
else
  ok "targeting: $(printf '%s\n' "$target" | awk '/^(org|space):/{printf "%s %s  ", $1, $2}')"

  buildpacks=$(cf buildpacks 2>/dev/null | awk '$1 ~ /^[0-9]+$/ {print $2}')
  for key in java-buildpack staticfile-buildpack; do
    name=$(get_var "$key")
    case "$key" in java-buildpack) hint=java ;; *) hint=static ;; esac
    if printf '%s\n' "$buildpacks" | grep -qx -- "$name"; then
      ok "buildpack $name"
    else
      # A foundation can have dozens of buildpacks - only suggest the plausible ones.
      candidates=$(printf '%s\n' "$buildpacks" | grep -i -- "$hint" | sort -u | tr '\n' ' ')
      fail "buildpack '$name' not found ($key) - candidates on this foundation: ${candidates:-none matching \"$hint\" (see cf buildpacks)}"
    fi
  done

  check_service() { # <var> <purpose>
    name=$(get_var "$1")
    if out=$(cf service "$name" 2>&1); then
      plan=$(printf '%s\n' "$out" | awk -F': *' '/^plan:/{print $2; exit}')
      ok "service $name${plan:+ (plan $plan)}"
      case "$plan" in
        *all*) warn "$name looks like an all-models plan: it is rejected as ambiguous at startup unless APP_GENAI_*_MODEL names a model - prefer a single-model plan" ;;
      esac
    else
      fail "service '$name' ($1, $2) not found - 'cf services' lists what exists"
    fi
  }
  check_service db-service "PostgreSQL with the pgvector extension"
  check_service chat-service "a CHAT model"
  check_service embed-service "a 768-dimension EMBEDDING model"
  if [ -n "$(get_var vision-service)" ]; then
    check_service vision-service "a VISION-capable CHAT model (optional: images and scanned PDFs)"
  else
    echo "  info  vision: off (set vision-service in vars.yml to read images and scanned PDFs)"
  fi
  case "$(get_var auth-mode)" in
    oidc)
      if [ -n "$(get_var sso-service)" ]; then
        check_service sso-service "the Tanzu SSO service (p-identity) the backend signs in with"
      else
        fail "auth-mode is oidc but sso-service is not set"
      fi ;;
    ""|jdbc) echo "  info  sign-in: local accounts (set auth-mode: oidc and sso-service in vars.yml for Tanzu SSO)" ;;
    *) fail "auth-mode '$(get_var auth-mode)' is not jdbc or oidc" ;;
  esac
  if [ "$batch" = true ] && [ "$(get_var ingestion-source)" = "filesystem" ]; then
    check_service volume-service "the document volume"
  fi

  domains=$(cf domains 2>/dev/null | awk '/^name/{f=1; next} f && NF {print $1}')
  for key in backend-route frontend-route; do
    route=$(get_var "$key"); found=""
    for d in $domains; do
      case "$route" in *".$d") found="$d" ;; esac
    done
    if [ -n "$found" ]; then ok "route $route (domain $found)"
    else fail "route '$route' ($key) is not under any domain 'cf domains' shows: $(printf '%s' "$domains" | tr '\n' ' ')"; fi
  done
fi

echo
if [ "$fails" -gt 0 ]; then
  echo "$fails problem(s), $warns warning(s) - fix the FAIL lines above, then re-run."
  exit 1
fi
echo "All checks passed ($warns warning(s))."
