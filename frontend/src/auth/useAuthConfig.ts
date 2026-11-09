import { useQuery } from "@tanstack/react-query";
import { authApi } from "../api/auth";
import type { AuthConfig } from "../api/types";

// What a failed lookup falls back to: the local username/password form. Harmless if the backend is
// actually in oidc mode - it rejects the login - and it is what every deployment did before modes.
const FALLBACK_CONFIG: AuthConfig = { mode: "jdbc", registrationEnabled: true, loginUrl: null };

// Which sign-in the backend runs (jdbc or oidc) is a deployment setting, so it never changes while
// the app is open: fetched once. Needs the consent banner acknowledged (everything does), which
// BannerGate guarantees for anything rendered under it. ["auth", ...] keys survive AuthContext's
// logout cache reset, so this is not refetched after signing out.
export function useAuthConfig(): { config: AuthConfig | undefined; isLoading: boolean } {
  const query = useQuery({
    queryKey: ["auth", "config"],
    queryFn: authApi.config,
    staleTime: Infinity,
    retry: false,
  });

  if (query.isError) {
    return { config: FALLBACK_CONFIG, isLoading: false };
  }
  return { config: query.data, isLoading: query.isLoading };
}
