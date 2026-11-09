import { describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useAuthConfig } from "./useAuthConfig";
import { authApi } from "../api/auth";

vi.mock("../api/auth", () => ({ authApi: { config: vi.fn() } }));

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useAuthConfig", () => {
  it("returns the server's configuration", async () => {
    const config = { mode: "oidc" as const, registrationEnabled: false, loginUrl: "/oauth2/authorization/sso" };
    vi.mocked(authApi.config).mockResolvedValue(config);

    const { result } = renderHook(() => useAuthConfig(), { wrapper });

    expect(result.current.isLoading).toBe(true);
    await waitFor(() => expect(result.current.config).toEqual(config));
  });

  it("falls back to the local username/password form when the lookup fails", async () => {
    vi.mocked(authApi.config).mockRejectedValue(new Error("boom"));

    const { result } = renderHook(() => useAuthConfig(), { wrapper });

    await waitFor(() =>
      expect(result.current.config).toEqual({ mode: "jdbc", registrationEnabled: true, loginUrl: null }),
    );
    expect(result.current.isLoading).toBe(false);
  });
});
