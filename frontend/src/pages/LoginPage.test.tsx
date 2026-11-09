import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { LoginPage } from "./LoginPage";
import { useAuth } from "../auth/AuthContext";
import { useAuthConfig } from "../auth/useAuthConfig";
import type { AuthConfig } from "../api/types";
import { ApiError } from "../api/client";

vi.mock("../auth/AuthContext", () => ({
  useAuth: vi.fn(),
}));

vi.mock("../auth/useAuthConfig", () => ({
  useAuthConfig: vi.fn(),
}));

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router-dom")>();
  return { ...actual, useNavigate: () => mockNavigate };
});

const jdbcConfig: AuthConfig = { mode: "jdbc", registrationEnabled: true, loginUrl: null };
const oidcConfig: AuthConfig = { mode: "oidc", registrationEnabled: false, loginUrl: "/oauth2/authorization/sso" };

function renderPage(path = "/login") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe("LoginPage", () => {
  const login = vi.fn();

  beforeEach(() => {
    login.mockReset();
    mockNavigate.mockReset();
    vi.mocked(useAuthConfig).mockReturnValue({ config: jdbcConfig, isLoading: false });
    vi.mocked(useAuth).mockReturnValue({
      user: null,
      isLoading: false,
      login,
      register: vi.fn(),
      logout: vi.fn(),
      refreshUser: vi.fn(),
    });
  });

  it("logs in with the entered credentials and navigates home on success", async () => {
    login.mockResolvedValue(undefined);
    renderPage();

    await userEvent.type(screen.getByLabelText("Username"), "alice");
    await userEvent.type(screen.getByLabelText("Password"), "password123");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));

    expect(login).toHaveBeenCalledWith("alice", "password123");
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/", { replace: true }));
  });

  it("shows the backend's error message and does not navigate on failure", async () => {
    login.mockRejectedValue(new ApiError(401, "Invalid username or password"));
    renderPage();

    await userEvent.type(screen.getByLabelText("Username"), "alice");
    await userEvent.type(screen.getByLabelText("Password"), "wrong-password");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByText("Invalid username or password")).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("hides the register link when registration is turned off", () => {
    vi.mocked(useAuthConfig).mockReturnValue({
      config: { ...jdbcConfig, registrationEnabled: false },
      isLoading: false,
    });
    renderPage();

    expect(screen.getByRole("button", { name: "Log in" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Register" })).not.toBeInTheDocument();
  });

  it("shows the register link when registration is enabled", () => {
    renderPage();

    expect(screen.getByRole("link", { name: "Register" })).toBeInTheDocument();
  });

  describe("in oidc mode", () => {
    const assign = vi.fn();

    beforeEach(() => {
      assign.mockReset();
      vi.mocked(useAuthConfig).mockReturnValue({ config: oidcConfig, isLoading: false });
      vi.stubGlobal("location", { ...window.location, assign });
    });

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it("offers only the SSO button - no credentials form and no register link", () => {
      renderPage();

      expect(screen.getByRole("button", { name: "Sign in with SSO" })).toBeInTheDocument();
      expect(screen.queryByLabelText("Username")).not.toBeInTheDocument();
      expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "Register" })).not.toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("navigates the whole page to the backend's authorization endpoint", async () => {
      renderPage();

      await userEvent.click(screen.getByRole("button", { name: "Sign in with SSO" }));

      expect(assign).toHaveBeenCalledWith("/oauth2/authorization/sso");
    });

    it("says so when the backend redirected back with login_error", () => {
      renderPage("/login?login_error");

      expect(screen.getByRole("alert")).toHaveTextContent(/Sign-in with SSO failed/);
    });

    it("disables the button and explains when the server has no sign-in url", () => {
      vi.mocked(useAuthConfig).mockReturnValue({ config: { ...oidcConfig, loginUrl: null }, isLoading: false });
      renderPage();

      expect(screen.getByRole("button", { name: "Sign in with SSO" })).toBeDisabled();
      expect(screen.getByRole("alert")).toHaveTextContent(/not configured/);
    });
  });
});
