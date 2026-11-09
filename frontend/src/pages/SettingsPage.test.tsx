import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SettingsPage } from "./SettingsPage";
import { useAuth } from "../auth/AuthContext";
import { useAuthConfig } from "../auth/useAuthConfig";
import { usersApi } from "../api/users";
import { ApiError } from "../api/client";
import type { User } from "../api/types";

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SettingsPage />
    </QueryClientProvider>,
  );
}

vi.mock("../auth/AuthContext", () => ({
  useAuth: vi.fn(),
}));

vi.mock("../auth/useAuthConfig", () => ({
  useAuthConfig: vi.fn(),
}));

vi.mock("../api/users", () => ({
  usersApi: {
    updateProfile: vi.fn(),
    changePassword: vi.fn(),
  },
}));

vi.mock("../components/ArchivedChatsSection", () => ({
  ArchivedChatsSection: () => <div>archived chats section</div>,
}));

const user: User = {
  id: 1,
  username: "alice",
  firstName: "Alice",
  lastName: "Anderson",
  email: "alice@example.com",
};

const refreshUser = vi.fn();

beforeEach(() => {
  vi.mocked(useAuthConfig).mockReturnValue({
    config: { mode: "jdbc", registrationEnabled: true, loginUrl: null },
    isLoading: false,
  });
  vi.mocked(useAuth).mockReturnValue({
    user,
    isLoading: false,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    refreshUser,
  });
});

describe("SettingsPage - Profile tab", () => {
  it("pre-fills fields from the current user and saves edits", async () => {
    vi.mocked(usersApi.updateProfile).mockResolvedValue({ ...user, firstName: "Alicia" });
    renderPage();

    expect(screen.getByDisplayValue("Alice")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Anderson")).toBeInTheDocument();
    expect(screen.getByDisplayValue("alice@example.com")).toBeInTheDocument();

    const firstNameInput = screen.getByDisplayValue("Alice");
    await userEvent.clear(firstNameInput);
    await userEvent.type(firstNameInput, "Alicia");
    await userEvent.click(screen.getByRole("button", { name: "Save profile" }));

    await waitFor(() =>
      expect(usersApi.updateProfile).toHaveBeenCalledWith({
        firstName: "Alicia",
        lastName: "Anderson",
        email: "alice@example.com",
      }),
    );
    expect(await screen.findByText("Profile saved.")).toBeInTheDocument();
    expect(refreshUser).toHaveBeenCalledWith({ ...user, firstName: "Alicia" });
  });
});

describe("SettingsPage - oidc mode", () => {
  it("has no Security tab, since there is no local password to change", () => {
    vi.mocked(useAuthConfig).mockReturnValue({
      config: { mode: "oidc", registrationEnabled: false, loginUrl: "/oauth2/authorization/sso" },
      isLoading: false,
    });
    renderPage();

    expect(screen.getByRole("button", { name: "Profile" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Archived Chats" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Security" })).not.toBeInTheDocument();
  });
});

describe("SettingsPage - Security tab", () => {
  it("changes the password on the happy path", async () => {
    vi.mocked(usersApi.changePassword).mockResolvedValue(undefined);
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Security" }));

    await userEvent.type(screen.getByLabelText("Current password"), "oldpassword123");
    await userEvent.type(screen.getByLabelText("New password"), "newpassword123");
    await userEvent.type(screen.getByLabelText("Confirm new password"), "newpassword123");
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));

    await waitFor(() =>
      expect(usersApi.changePassword).toHaveBeenCalledWith({
        currentPassword: "oldpassword123",
        newPassword: "newpassword123",
      }),
    );
    expect(await screen.findByText("Password changed.")).toBeInTheDocument();
  });

  it("shows the backend's error message when the current password is wrong", async () => {
    vi.mocked(usersApi.changePassword).mockRejectedValue(new ApiError(400, "Current password is incorrect"));
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Security" }));

    await userEvent.type(screen.getByLabelText("Current password"), "wrong-password");
    await userEvent.type(screen.getByLabelText("New password"), "newpassword123");
    await userEvent.type(screen.getByLabelText("Confirm new password"), "newpassword123");
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByText("Current password is incorrect")).toBeInTheDocument();
  });

  it("rejects mismatched new/confirm passwords without calling the API", async () => {
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Security" }));

    await userEvent.type(screen.getByLabelText("Current password"), "oldpassword123");
    await userEvent.type(screen.getByLabelText("New password"), "newpassword123");
    await userEvent.type(screen.getByLabelText("Confirm new password"), "different456");
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByText("New passwords do not match.")).toBeInTheDocument();
    expect(usersApi.changePassword).not.toHaveBeenCalled();
  });
});

describe("SettingsPage - Archived Chats tab", () => {
  it("renders the archived chats section", async () => {
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Archived Chats" }));

    expect(await screen.findByText("archived chats section")).toBeInTheDocument();
  });
});
