import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext";

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return null;
  }

  if (!user) {
    // Keep the query string: after a failed SSO sign-in the backend redirects to "/?login_error",
    // and LoginPage reads that flag to say what happened.
    return <Navigate to={{ pathname: "/login", search: location.search }} replace />;
  }

  return children;
}
