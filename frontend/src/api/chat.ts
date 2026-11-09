import { api, ApiError, fetchWithCsrf } from "./client";
import type { ChatMessage, ChatSession, Citation } from "./types";

export const chatApi = {
  list: (archived = false) => api.get<ChatSession[]>(`/api/chats?archived=${archived}`),
  get: (id: number) => api.get<ChatSession>(`/api/chats/${id}`),
  create: () => api.post<ChatSession>("/api/chats"),
  rename: (id: number, title: string) => api.patch<ChatSession>(`/api/chats/${id}`, { title }),
  archive: (id: number) => api.post<ChatSession>(`/api/chats/${id}/archive`),
  unarchive: (id: number) => api.post<ChatSession>(`/api/chats/${id}/unarchive`),
  remove: (id: number) => api.delete<void>(`/api/chats/${id}`),
  getMessages: (id: number) => api.get<ChatMessage[]>(`/api/chats/${id}/messages`),
};

export interface StreamHandlers {
  onToken: (text: string) => void;
  onSources: (sources: Citation[]) => void;
  onError: (error: unknown) => void;
  onDone: () => void;
}

/**
 * The native EventSource API can't send a POST body, so the SSE stream is parsed by hand over
 * fetch()'s ReadableStream instead. Spring's ServerSentEvent.format() emits "event:<type>\n" and
 * "data:<json>\n" lines (no space after the colon) separated by a blank line per event.
 */
export function sendMessageStream(
  sessionId: number,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): void {
  void (async () => {
    try {
      const response = await fetchWithCsrf(`/api/chats/${sessionId}/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify({ content }),
        signal,
      });

      if (!response.ok || !response.body) {
        throw new ApiError(response.status, response.statusText);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let separatorIndex = buffer.indexOf("\n\n");
        while (separatorIndex !== -1) {
          dispatchEvent(buffer.slice(0, separatorIndex), handlers);
          buffer = buffer.slice(separatorIndex + 2);
          separatorIndex = buffer.indexOf("\n\n");
        }
      }

      handlers.onDone();
    } catch (error) {
      if ((error as Error).name !== "AbortError") {
        handlers.onError(error);
      }
    }
  })();
}

function dispatchEvent(rawEvent: string, handlers: StreamHandlers): void {
  let eventType = "message";
  let data = "";
  for (const line of rawEvent.split("\n")) {
    if (line.startsWith("event:")) {
      eventType = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      data += line.slice("data:".length);
    }
  }

  if (!data) return;

  if (eventType === "token") {
    handlers.onToken((JSON.parse(data) as { text: string }).text);
  } else if (eventType === "sources") {
    handlers.onSources(JSON.parse(data) as Citation[]);
  }
}
