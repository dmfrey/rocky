import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, clearCsrfToken } from "./client";
import { authApi } from "./auth";

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response>;

function stubFetch(handler: Handler) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => handler(url, init));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function csrfResponse(token: string) {
  return Response.json({ headerName: "X-CSRF-TOKEN", token });
}

function csrfCalls(fetchMock: ReturnType<typeof stubFetch>) {
  return fetchMock.mock.calls.filter(([url]) => url.endsWith("/api/csrf"));
}

function headersOf(call: unknown[]) {
  return (call[1] as RequestInit).headers as Record<string, string>;
}

afterEach(() => {
  vi.unstubAllGlobals();
  clearCsrfToken();
});

describe("CSRF token handling", () => {
  it("does not fetch a token for GET requests", async () => {
    const fetchMock = stubFetch(() => Response.json({ ok: true }));

    await api.get("/api/chats");

    expect(csrfCalls(fetchMock)).toHaveLength(0);
    expect(headersOf(fetchMock.mock.calls[0])["X-CSRF-TOKEN"]).toBeUndefined();
  });

  it("fetches the token once and sends it on every mutating request, including multipart uploads", async () => {
    const fetchMock = stubFetch((url) => (url.endsWith("/api/csrf") ? csrfResponse("t1") : Response.json({})));

    await api.post("/api/chats");
    await api.patch("/api/chats/1", { title: "x" });
    await api.put("/api/users/me/password", {});
    await api.delete("/api/chats/1");
    await api.postForm("/api/documents", new FormData());

    expect(csrfCalls(fetchMock)).toHaveLength(1);
    const mutations = fetchMock.mock.calls.filter(([url]) => !url.endsWith("/api/csrf"));
    expect(mutations).toHaveLength(5);
    for (const call of mutations) {
      expect(headersOf(call)["X-CSRF-TOKEN"]).toBe("t1");
      expect((call[1] as RequestInit).credentials).toBe("include");
    }
    // The multipart body must not get a JSON content type (the browser sets the boundary).
    expect(headersOf(mutations[4])["Content-Type"]).toBeUndefined();
  });

  it("shares one in-flight token request between concurrent mutations", async () => {
    const fetchMock = stubFetch((url) => (url.endsWith("/api/csrf") ? csrfResponse("t1") : Response.json({})));

    await Promise.all([api.post("/api/a"), api.post("/api/b"), api.post("/api/c")]);

    expect(csrfCalls(fetchMock)).toHaveLength(1);
  });

  it("refetches the token and retries once when a mutating request gets 403", async () => {
    let token = 0;
    const fetchMock = stubFetch((url, init) => {
      if (url.endsWith("/api/csrf")) return csrfResponse(`t${++token}`);
      return (init!.headers as Record<string, string>)["X-CSRF-TOKEN"] === "t2"
        ? Response.json({ ok: true })
        : new Response(null, { status: 403 });
    });

    await expect(api.post("/api/chats")).resolves.toEqual({ ok: true });

    expect(csrfCalls(fetchMock)).toHaveLength(2);
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith("/api/chats"))).toHaveLength(2);
  });

  it("gives up after a single retry and surfaces the 403", async () => {
    const fetchMock = stubFetch((url) =>
      url.endsWith("/api/csrf") ? csrfResponse("t") : Response.json({ error: "Forbidden" }, { status: 403 }),
    );

    const error = await api.post("/api/chats").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith("/api/chats"))).toHaveLength(2);
  });

  it("does not retry a 403 on a GET", async () => {
    const fetchMock = stubFetch(() => new Response(null, { status: 403 }));

    await expect(api.get("/api/chats")).rejects.toBeInstanceOf(ApiError);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not cache a failed token fetch", async () => {
    let fail = true;
    stubFetch((url) => {
      if (url.endsWith("/api/csrf")) return fail ? new Response(null, { status: 500 }) : csrfResponse("t1");
      return Response.json({});
    });

    await expect(api.post("/api/chats")).rejects.toBeInstanceOf(ApiError);
    fail = false;
    await expect(api.post("/api/chats")).resolves.toEqual({});
  });

  it("drops the cached token after login and after logout", async () => {
    let token = 0;
    const fetchMock = stubFetch((url) => {
      if (url.endsWith("/api/csrf")) return csrfResponse(`t${++token}`);
      if (url.endsWith("/api/auth/logout")) return new Response(null, { status: 204 });
      return Response.json({ id: 1, username: "alice" });
    });

    await authApi.login("alice", "pw"); // fetches t1
    await api.post("/api/chats"); // login cleared it -> fetches t2
    await authApi.logout(); // uses t2, then clears
    await api.post("/api/chats"); // fetches t3

    expect(csrfCalls(fetchMock)).toHaveLength(3);
    const last = fetchMock.mock.calls.filter(([url]) => url.endsWith("/api/chats")).at(-1)!;
    expect(headersOf(last)["X-CSRF-TOKEN"]).toBe("t3");
  });

  it("returns the oidc logout url from the logout response", async () => {
    stubFetch((url) =>
      url.endsWith("/api/csrf") ? csrfResponse("t") : Response.json({ logoutUrl: "https://idp.example/logout" }),
    );

    await expect(authApi.logout()).resolves.toEqual({ logoutUrl: "https://idp.example/logout" });
  });
});
