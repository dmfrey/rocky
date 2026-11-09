import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useAuthConfig } from "../auth/useAuthConfig";
import { API_BASE_URL, ApiError } from "../api/client";
import { AuthCard } from "../components/AuthCard";

// A loginUrl is a backend path; an absolute one (an explicitly configured IdP entry point) is used as is.
function signInUrl(loginUrl: string): string {
  return /^https?:\/\//.test(loginUrl) ? loginUrl : `${API_BASE_URL}${loginUrl}`;
}

export function LoginPage() {
  const { login } = useAuth();
  const { config, isLoading: configLoading } = useAuthConfig();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      await login(username, password);
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.");
    } finally {
      setIsSubmitting(false);
    }
  }

  if (configLoading || !config) {
    return (
      <AuthCard title="Log in">
        <p role="status" className="subtle-text">
          Loading…
        </p>
      </AuthCard>
    );
  }

  if (config.mode === "oidc") {
    return (
      <AuthCard title="Log in">
        <div className="flex flex-col gap-4">
          {searchParams.has("login_error") && (
            <p role="alert" className="alert alert-danger">
              Sign-in with SSO failed. Please try again, or contact your administrator if it keeps happening.
            </p>
          )}
          {!config.loginUrl && (
            <p role="alert" className="alert alert-danger">
              Single sign-on is not configured on the server.
            </p>
          )}
          <button
            type="button"
            disabled={!config.loginUrl}
            onClick={() => window.location.assign(signInUrl(config.loginUrl!))}
            className="btn btn-primary"
          >
            Sign in with SSO
          </button>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Log in">
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <label className="form-label">
          Username
          <input
            type="text"
            required
            autoFocus
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            className="form-control"
          />
        </label>
        <label className="form-label">
          Password
          <input
            type="password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="form-control"
          />
        </label>
        {error && (
          <p role="alert" className="alert alert-danger">
            {error}
          </p>
        )}
        <button type="submit" disabled={isSubmitting} className="btn btn-primary">
          {isSubmitting ? "Logging in…" : "Log in"}
        </button>
      </form>
      {config.registrationEnabled && (
        <p className="mt-4 text-center text-sm text-gray-600 dark:text-gray-400">
          Don&apos;t have an account?{" "}
          <Link to="/register" className="font-medium hover:underline">
            Register
          </Link>
        </p>
      )}
    </AuthCard>
  );
}
