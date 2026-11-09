import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { RegisterPage } from "./RegisterPage";
import { useAuth } from "../auth/AuthContext";
import { useAuthConfig } from "../auth/useAuthConfig";

vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));
vi.mock("../auth/useAuthConfig", () => ({ useAuthConfig: vi.fn() }));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/register"]}>
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.mocked(useAuth).mockReturnValue({
    user: null,
    isLoading: false,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    refreshUser: vi.fn(),
  });
});

describe("RegisterPage", () => {
  it("shows the form in jdbc mode with registration enabled", () => {
    vi.mocked(useAuthConfig).mockReturnValue({
      config: { mode: "jdbc", registrationEnabled: true, loginUrl: null },
      isLoading: false,
    });
    renderPage();

    expect(screen.getByRole("button", { name: "Create account" })).toBeInTheDocument();
  });

  it("redirects to /login when registration is turned off", () => {
    vi.mocked(useAuthConfig).mockReturnValue({
      config: { mode: "jdbc", registrationEnabled: false, loginUrl: null },
      isLoading: false,
    });
    renderPage();

    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  it("redirects to /login in oidc mode", () => {
    vi.mocked(useAuthConfig).mockReturnValue({
      config: { mode: "oidc", registrationEnabled: false, loginUrl: "/oauth2/authorization/sso" },
      isLoading: false,
    });
    renderPage();

    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  it("renders nothing while the config loads", () => {
    vi.mocked(useAuthConfig).mockReturnValue({ config: undefined, isLoading: true });
    renderPage();

    expect(screen.queryByText("login page")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create account" })).not.toBeInTheDocument();
  });
});
