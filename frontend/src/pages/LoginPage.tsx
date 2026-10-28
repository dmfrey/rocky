import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { AuthCard } from "../components/AuthCard";

export function LoginPage() {
  const { login } = useAuth();
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
      setError(
        err instanceof ApiError
          ? err.message
          : "Something went wrong. Please try again.",
      );
    } finally {
      setIsSubmitting(false);
    }
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
        <button
          type="submit"
          disabled={isSubmitting}
          className="btn btn-primary"
        >
          {isSubmitting ? "Logging in…" : "Log in"}
        </button>
      </form>
      <p className="mt-4 text-center text-sm text-gray-600 dark:text-gray-400">
        Don&apos;t have an account?{" "}
        <Link to="/register" className="font-medium hover:underline">
          Register
        </Link>
      </p>
    </AuthCard>
  );
}
