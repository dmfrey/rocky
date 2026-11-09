#!/usr/bin/env bash
# Switch the backend to single sign-on with the Tanzu SSO tile (a p-identity service instance).
#
#   ./enable-sso.sh            bind sso-service from vars.yml, select oidc mode, restart
#
# Does nothing (and says so) unless auth-mode is oidc in vars.yml. deploy.sh runs this for you after
# the push, so you normally only run it by hand to switch an already-deployed system.
# If the executable bit was lost in transit, run it as `bash enable-sso.sh`.
#
# Like vision, this is done with cf commands rather than manifest.yml: a manifest cannot bind a service
# conditionally. `cf push` keeps the binding and the environment variables, so later deploys do not
# undo it. In oidc mode there are no local accounts: sign-in, registration and password change are
# the identity provider's, and the first SSO login of a user creates their profile row.
#
# The redirect URIs are registered with the SSO service AT BIND TIME, so they must be right now:
#   - https://<backend-route>/login/oauth2/code/sso   where the identity provider sends the browser back
#   - https://<frontend-route>/                        where the browser lands after sign-in / sign-out
# They default to the routes in vars.yml, passed as the broker's required `redirect_uris` parameter (the plan
# declares no parameter schema, so `cf marketplace` does not show it; the same form binds dmfrey/stig's
# stig-sso). sso-bind-params (a complete JSON object) replaces the whole parameter set if you need more.
#
# To go back to local accounts:
#   cf set-env <backend-app> APP_AUTH_MODE jdbc && cf restart <backend-app>
set -eu
cd "$(dirname "$0")" || exit 1
. ./lib.sh

mode=$(get_var auth-mode)
if [ "$mode" != "oidc" ]; then
  echo "auth-mode is not oidc in $VARS - the backend keeps local (jdbc) accounts."
  exit 0
fi

service=$(get_var sso-service)
if [ -z "$service" ]; then
  echo "auth-mode is oidc but sso-service is not set in $VARS" >&2
  exit 1
fi

backend=$(get_var backend-app)
params=$(get_var sso-bind-params)
if [ -z "$params" ]; then
  params="{\"redirect_uris\":[\"https://$(get_var backend-route)/login/oauth2/code/sso\",\"https://$(get_var frontend-route)/\"]}"
fi

echo "Binding $service to $backend with $params"
cf bind-service "$backend" "$service" -c "$params"
cf set-env "$backend" APP_AUTH_MODE oidc
cf set-env "$backend" APP_AUTH_OIDC_SERVICE "$service"

echo "Restarting $backend to pick up the binding"
if ! cf restart "$backend"; then
  echo >&2
  echo "The backend did not come back up. Likely causes: the binding has no client_id/client_secret/auth_domain" >&2
  echo "(see 'cf service-key'-style output for the service), or the issuer cannot be reached. To go back to local accounts:" >&2
  echo "  cf set-env $backend APP_AUTH_MODE jdbc && cf restart $backend" >&2
  exit 1
fi
