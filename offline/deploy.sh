#!/usr/bin/env bash
# One command to deploy: point the frontend at your backend, check everything, then cf push.
#
#   ./deploy.sh frontend-dist.tar.gz    first time (extracts the frontend bundle)
#   ./deploy.sh                         later pushes (frontend already extracted and prepared)
#
# Options:
#   --with-batch      also deploy the optional batch job (manifest-batch.yml)
#   --without-batch   do not, even if deploy-batch is true in vars.yml
#   --dry-run         do everything except the cf push, and print what would be pushed
#
# Without --with-batch / --without-batch, deploy-batch in vars.yml decides (false if unset).
# If the executable bit was lost in transit, run it as `bash deploy.sh`.
#
# manifest.yml (backend + frontend) is pushed first. The batch job, if wanted, is pushed in a
# second step only once that has succeeded - by then the backend is running and has migrated the
# database, and sharepoint-batch must never run against a database the backend has not migrated
# at least once.
set -eu
cd "$(dirname "$0")" || exit 1
. ./lib.sh

bundle=""
dry_run=""
while [ $# -gt 0 ]; do
  case "$1" in
    --with-batch)    export DEPLOY_BATCH=true ;;
    --without-batch) export DEPLOY_BATCH=false ;;
    --dry-run)       dry_run=1 ;;
    -h|--help)       awk 'NR > 1 && /^#/ {print; next} NR > 1 {exit}' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)              echo "Unknown option: $1 (see ./deploy.sh --help)" >&2; exit 2 ;;
    *)               bundle="$1" ;;
  esac
  shift
done

# Run the helpers through bash explicitly so a lost executable bit (zip, USB stick, some file shares)
# cannot break the deploy.
if [ -n "$bundle" ]; then bash ./prepare-frontend.sh "$bundle"; else bash ./prepare-frontend.sh; fi
echo
bash ./preflight.sh
echo

# Optional stack (e.g. tanzucflinuxfs1 for a foundation with several): passed as --stack, because a
# manifest cannot leave a field unset. Empty/absent = the foundation's default stack.
stack=$(get_var stack)
push() { # <manifest>
  echo "cf push -f $1 --vars-file $VARS${stack:+ --stack $stack}"
  [ -n "$dry_run" ] || cf push -f "$1" --vars-file "$VARS" ${stack:+--stack "$stack"}
}

push "$MANIFEST"
if [ "$(want_batch)" = true ]; then
  echo
  push "$BATCH_MANIFEST"
else
  echo
  echo "Batch job not deployed. Add it later with ./deploy.sh --with-batch (or set deploy-batch: true)."
fi
# Optional chat timeout for slow models: set with cf set-env (a manifest cannot leave an env var
# unset), then picked up by the restart below if vision is enabled, or by one here if not.
timeout=$(get_var chat-timeout)
if [ -n "$timeout" ]; then
  echo
  echo "cf set-env $(get_var backend-app) APP_GENAI_CHAT_TIMEOUT $timeout"
  if [ -z "$dry_run" ]; then
    cf set-env "$(get_var backend-app)" APP_GENAI_CHAT_TIMEOUT "$timeout"
    [ -n "$(get_var vision-service)" ] || cf restart "$(get_var backend-app)"
  fi
fi
# Optional vision model (images / scanned PDFs): bound with cf commands after the push, because a
# manifest cannot bind a service conditionally. Skipped when vision-service is empty.
if [ -n "$(get_var vision-service)" ]; then
  echo
  if [ -n "$dry_run" ]; then
    echo "bash ./enable-vision.sh   (binds $(get_var vision-service) and restarts the backend)"
  else
    bash ./enable-vision.sh
  fi
fi
[ -z "$dry_run" ] || echo "(dry run: nothing was pushed)"
