import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { AuthCard } from "../components/AuthCard";

export function RegisterPage() {
  const { register, login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      // Registration alone doesn't establish a session on the backend - log in right after so a
      // new account lands the user straight in the app instead of a second manual login step.
      await register({ username, password, firstName, lastName, email });
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
    <AuthCard title="Create an account">
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
        <div className="flex gap-3">
          <label className="form-label">
            First name
            <input
              type="text"
              required
              value={firstName}
              onChange={(event) => setFirstName(event.target.value)}
              className="form-control"
            />
          </label>
          <label className="form-label">
            Last name
            <input
              type="text"
              required
              value={lastName}
              onChange={(event) => setLastName(event.target.value)}
              className="form-control"
            />
          </label>
        </div>
        <label className="form-label">
          Email
          <input
            type="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="form-control"
          />
        </label>
        <label className="form-label">
          Password
          <input
            type="password"
            required
            minLength={8}
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
          {isSubmitting ? "Creating account…" : "Create account"}
        </button>
      </form>
      <p className="mt-4 text-center text-sm text-gray-600 dark:text-gray-400">
        Already have an account?{" "}
        <Link to="/login" className="font-medium hover:underline">
          Log in
        </Link>
      </p>
    </AuthCard>
  );
}
