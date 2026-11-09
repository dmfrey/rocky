import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { RequireAuth } from "./RequireAuth";
import { useAuth } from "./AuthContext";

vi.mock("./AuthContext", () => ({ useAuth: vi.fn() }));

function LoginProbe() {
  const location = useLocation();
  return <p>login page{location.search}</p>;
}

describe("RequireAuth", () => {
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

  it("keeps the query string when it sends an unauthenticated visitor to /login", () => {
    render(
      <MemoryRouter initialEntries={["/?login_error"]}>
        <Routes>
          <Route
            path="/"
            element={
              <RequireAuth>
                <p>app</p>
              </RequireAuth>
            }
          />
          <Route path="/login" element={<LoginProbe />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("login page?login_error")).toBeInTheDocument();
  });
});
