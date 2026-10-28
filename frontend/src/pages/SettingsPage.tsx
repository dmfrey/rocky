import { useState, type FormEvent } from "react";
import { useMutation } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { usersApi } from "../api/users";
import { ApiError } from "../api/client";
import { ArchivedChatsSection } from "../components/ArchivedChatsSection";

type Tab = "profile" | "security" | "archived";

const TABS: { id: Tab; label: string }[] = [
  { id: "profile", label: "Profile" },
  { id: "security", label: "Security" },
  { id: "archived", label: "Archived Chats" },
];

export function SettingsPage() {
  const [activeTab, setActiveTab] = useState<Tab>("profile");

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <h1>Settings</h1>
          <p>
            Manage your profile, account security, and archived conversations.
          </p>
        </div>
      </div>
      <div className="settings-content">
        <div className="settings-tabs" aria-label="Settings sections">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id)}
              aria-pressed={activeTab === tab.id}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <div className="panel settings-panel">
          {activeTab === "profile" && <ProfileSection />}
          {activeTab === "security" && <SecuritySection />}
          {activeTab === "archived" && <ArchivedChatsSection />}
        </div>
      </div>
    </div>
  );
}

function ProfileSection() {
  const { user, refreshUser } = useAuth();
  const [firstName, setFirstName] = useState(user?.firstName ?? "");
  const [lastName, setLastName] = useState(user?.lastName ?? "");
  const [email, setEmail] = useState(user?.email ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const updateMutation = useMutation({
    mutationFn: (input: {
      firstName: string;
      lastName: string;
      email: string;
    }) => usersApi.updateProfile(input),
    onSuccess: (updatedUser) => {
      refreshUser(updatedUser);
      setSaved(true);
      setError(null);
    },
    onError: (err) => {
      setSaved(false);
      setError(
        err instanceof ApiError
          ? err.message
          : "Something went wrong. Please try again.",
      );
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    updateMutation.mutate({ firstName, lastName, email });
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <div className="flex gap-3">
        <label className="form-label">
          First name
          <input
            type="text"
            value={firstName}
            onChange={(event) => setFirstName(event.target.value)}
            className="form-control"
          />
        </label>
        <label className="form-label">
          Last name
          <input
            type="text"
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
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          className="form-control"
        />
      </label>
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      {saved && !error && (
        <p role="status" className="alert alert-success">
          Profile saved.
        </p>
      )}
      <button
        type="submit"
        disabled={updateMutation.isPending}
        className="btn btn-primary w-fit"
      >
        {updateMutation.isPending ? "Saving…" : "Save profile"}
      </button>
    </form>
  );
}

function SecuritySection() {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [succeeded, setSucceeded] = useState(false);

  const changePasswordMutation = useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) =>
      usersApi.changePassword(input),
    onSuccess: () => {
      setSucceeded(true);
      setError(null);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
    },
    onError: (err) => {
      setSucceeded(false);
      setError(
        err instanceof ApiError
          ? err.message
          : "Something went wrong. Please try again.",
      );
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSucceeded(false);
    if (newPassword !== confirmPassword) {
      setError("New passwords do not match.");
      return;
    }
    setError(null);
    changePasswordMutation.mutate({ currentPassword, newPassword });
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <label className="form-label">
        Current password
        <input
          type="password"
          required
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          className="form-control"
        />
      </label>
      <label className="form-label">
        New password
        <input
          type="password"
          required
          minLength={8}
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          className="form-control"
        />
      </label>
      <label className="form-label">
        Confirm new password
        <input
          type="password"
          required
          minLength={8}
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          className="form-control"
        />
      </label>
      {error && (
        <p role="alert" className="alert alert-danger">
          {error}
        </p>
      )}
      {succeeded && !error && (
        <p role="status" className="alert alert-success">
          Password changed.
        </p>
      )}
      <button
        type="submit"
        disabled={changePasswordMutation.isPending}
        className="btn btn-primary w-fit"
      >
        {changePasswordMutation.isPending ? "Changing…" : "Change password"}
      </button>
    </form>
  );
}
