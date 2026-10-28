#!/usr/bin/env bash
# Turn on the OPTIONAL vision model: image files (.png .jpg .gif) and scanned PDFs.
#
#   ./enable-vision.sh            bind vision-service from vars.yml and restart
#
# Does nothing (and says so) when vision-service is empty in vars.yml. deploy.sh runs this for you
# after the push, so you normally only run it by hand to add vision to an already-deployed system.
# If the executable bit was lost in transit, run it as `bash enable-vision.sh`.
#
# A CF manifest cannot bind a service conditionally, so this is done with cf commands instead of in
# manifest.yml: it binds the service and sets APP_GENAI_VISION_SERVICE, which is what switches the
# feature on (without that setting the apps have no vision model and image/scanned files simply fail
# ingestion with a clear message). `cf push` keeps both, so later deploys do not undo it.
#
# A service whose plan has no VISION model does not stop the backend: it starts, logs a warning, and
# images / scanned PDFs fail with a message saying the service grants no VISION model.
#
# To turn it off again:
#   cf unset-env <backend-app> APP_GENAI_VISION_SERVICE && cf unbind-service <backend-app> <service>
#   (same for <batch-app>), then restart the backend.
set -eu
cd "$(dirname "$0")" || exit 1
. ./lib.sh

service=$(get_var vision-service)
if [ -z "$service" ]; then
  echo "vision-service is not set in $VARS - vision (images / scanned PDFs) stays off."
  exit 0
fi

backend=$(get_var backend-app)
apps="$backend"
[ "$(want_batch)" = true ] && apps="$apps $(get_var batch-app)"

for app in $apps; do
  echo "Enabling vision for $app (service $service)"
  cf bind-service "$app" "$service"
  cf set-env "$app" APP_GENAI_VISION_SERVICE "$service"
done
# The backend is running; the batch job (instances 0) just picks it up on its next task.
echo "Restarting $backend to pick up the binding"
if ! cf restart "$backend"; then
  echo >&2
  echo "The backend did not come back up. To undo vision and recover:" >&2
  echo "  cf unset-env $backend APP_GENAI_VISION_SERVICE && cf restart $backend" >&2
  exit 1
fi
