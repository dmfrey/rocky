import { afterEach, describe, expect, it, vi } from "vitest";
import { sendMessageStream } from "./chat";
import { clearCsrfToken } from "./client";
import type { Citation } from "./types";

function streamFromChunks(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let index = 0;
  return new ReadableStream({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index]));
        index += 1;
      } else {
        controller.close();
      }
    },
  });
}

function mockStreamingResponse(chunks: string[], status = 200) {
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) =>
    url.endsWith("/api/csrf")
      ? Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-1" })
      : new Response(streamFromChunks(chunks), { status }),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  clearCsrfToken();
});

describe("sendMessageStream", () => {
  it("dispatches token events as they arrive, even when one is split across chunk boundaries", async () => {
    const tokens: string[] = [];
    let sources: Citation[] | null = null;

    // "Hello" is deliberately split mid-JSON-string across two raw chunks, matching how a real
    // fetch ReadableStream can hand back partial SSE events - this is the actual bug risk in
    // sendMessageStream's hand-rolled buffering, not something a happy-path single chunk would catch.
    mockStreamingResponse([
      'event:token\ndata:{"text":"Hel',
      'lo"}\n\nevent:token\ndata:{"text":" world"}\n\n',
      'event:sources\ndata:[{"documentId":1,"filename":"a.pdf"}]\n\n',
    ]);

    await new Promise<void>((resolve, reject) => {
      sendMessageStream(1, "hi", {
        onToken: (text) => tokens.push(text),
        onSources: (received) => {
          sources = received;
        },
        onError: reject,
        onDone: resolve,
      });
    });

    expect(tokens).toEqual(["Hello", " world"]);
    expect(sources).toEqual([{ documentId: 1, filename: "a.pdf" }]);
  });

  it("sends the CSRF token with the POST", async () => {
    const fetchMock = mockStreamingResponse(['event:token\ndata:{"text":"hi"}\n\n']);

    await new Promise<void>((resolve, reject) => {
      sendMessageStream(1, "hi", { onToken: () => {}, onSources: () => {}, onError: reject, onDone: resolve });
    });

    const post = fetchMock.mock.calls.find(([url]) => url.endsWith("/messages"))!;
    expect((post[1] as RequestInit).headers).toMatchObject({ "X-CSRF-TOKEN": "csrf-1" });
  });

  it("calls onError, not onDone, when the response is not ok", async () => {
    mockStreamingResponse([], 500);

    const onDone = vi.fn();
    const error = await new Promise<unknown>((resolve) => {
      sendMessageStream(1, "hi", {
        onToken: () => {},
        onSources: () => {},
        onError: resolve,
        onDone,
      });
    });

    expect(error).toBeInstanceOf(Error);
    expect(onDone).not.toHaveBeenCalled();
  });

  it("ignores events of an unrecognized type", async () => {
    mockStreamingResponse(['event:ping\ndata:{}\n\nevent:token\ndata:{"text":"hi"}\n\n']);

    const tokens: string[] = [];
    await new Promise<void>((resolve, reject) => {
      sendMessageStream(1, "hi", {
        onToken: (text) => tokens.push(text),
        onSources: () => {},
        onError: reject,
        onDone: resolve,
      });
    });

    expect(tokens).toEqual(["hi"]);
  });
});
