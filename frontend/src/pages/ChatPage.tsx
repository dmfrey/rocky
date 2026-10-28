import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { chatApi, sendMessageStream } from "../api/chat";
import { Icon } from "../components/Icon";
import type { ChatMessage, Citation } from "../api/types";

export function ChatPage() {
  const { id } = useParams<{ id: string }>();
  const sessionId = Number(id);
  const queryClient = useQueryClient();

  const sessionQuery = useQuery({
    queryKey: ["chats", sessionId],
    queryFn: () => chatApi.get(sessionId),
  });

  const messagesQuery = useQuery({
    queryKey: ["chats", sessionId, "messages"],
    queryFn: () => chatApi.getMessages(sessionId),
  });

  const [input, setInput] = useState("");
  const [pendingUserMessage, setPendingUserMessage] = useState<string | null>(
    null,
  );
  const [streamingText, setStreamingText] = useState("");
  const [streamingSources, setStreamingSources] = useState<Citation[] | null>(
    null,
  );
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamError, setStreamError] = useState<string | null>(null);

  const bottomRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messagesQuery.data, streamingText, pendingUserMessage]);

  function submitMessage() {
    const content = input.trim();
    if (!content || isStreaming) return;

    setInput("");
    setPendingUserMessage(content);
    setStreamingText("");
    setStreamingSources(null);
    setStreamError(null);
    setIsStreaming(true);

    sendMessageStream(sessionId, content, {
      onToken: (text) => setStreamingText((prev) => prev + text),
      onSources: (sources) => setStreamingSources(sources),
      onError: () => {
        setStreamError(
          "Something went wrong while streaming the response. Please try again.",
        );
        setIsStreaming(false);
      },
      onDone: async () => {
        await queryClient.invalidateQueries({
          queryKey: ["chats", sessionId, "messages"],
        });
        void queryClient.invalidateQueries({ queryKey: ["chats"] }); // picks up the auto-generated title
        setPendingUserMessage(null);
        setStreamingText("");
        setStreamingSources(null);
        setIsStreaming(false);
      },
    });
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    submitMessage();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      submitMessage();
    }
  }

  const messages = messagesQuery.data ?? [];

  return (
    <div className="chat-page">
      <div className="chat-heading">
        <div>
          <p className="eyebrow">CONVERSATION</p>
          <h1>{sessionQuery.data?.title ?? "New chat"}</h1>
        </div>
        <span className="chat-context">
          <Icon name="documents" size={16} /> Document knowledge base
        </span>
      </div>

      <div className="chat-scroll">
        <div className="message-list">
          {messagesQuery.isLoading && (
            <p role="status" className="subtle-text">
              Loading conversation…
            </p>
          )}
          {(messagesQuery.isError || sessionQuery.isError) && (
            <p role="alert" className="alert alert-danger">
              Could not load this conversation. Please refresh to try again.
            </p>
          )}
          {!messagesQuery.isLoading &&
            !messagesQuery.isError &&
            messages.length === 0 &&
            !pendingUserMessage && (
              <div className="chat-empty">
                <Icon name="chat" size={32} />
                <h2>What would you like to know?</h2>
                <p>
                  Ask a question about the documents in your knowledge base.
                </p>
              </div>
            )}
          {messages.map((message, index) => (
            <MessageBubble key={index} message={message} />
          ))}

          {pendingUserMessage && (
            <MessageBubble
              message={{
                role: "USER",
                content: pendingUserMessage,
                citations: [],
              }}
            />
          )}

          {isStreaming && (
            <MessageBubble
              message={{
                role: "ASSISTANT",
                content:
                  streamingText ||
                  "Searching your documents and preparing an answer…",
                citations: streamingSources ?? [],
              }}
            />
          )}

          {streamError && (
            <p role="alert" className="alert alert-danger">
              {streamError}
            </p>
          )}

          <div ref={bottomRef} />
        </div>
      </div>

      <form onSubmit={handleSubmit} className="chat-composer">
        <div className="composer-input">
          <textarea
            value={input}
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={handleKeyDown}
            aria-label="Message"
            rows={2}
            placeholder="Ask a question grounded in the uploaded documents…"
            className="composer-textarea"
          />
          <button
            type="submit"
            disabled={isStreaming || !input.trim()}
            className="btn btn-primary"
          >
            <Icon name="send" size={16} /> Send
          </button>
        </div>
        <p className="composer-help">
          <span>Enter to send · Shift + Enter for a new line</span>
          <span>Verify answers against their sources.</span>
        </p>
      </form>
    </div>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === "USER";
  return (
    <article
      className={`message ${isUser ? "message-user" : "message-assistant"}`}
    >
      <div className="message-avatar">
        <Icon name={isUser ? "user" : "chat"} size={18} />
      </div>
      <div className="message-body">
        <div className="message-author">
          {isUser ? "You" : "Rocky"}
          {!isUser && <span>Document assistant</span>}
        </div>
        <div className="message-content">
          {isUser ? (
            message.content
          ) : (
            <MarkdownContent content={message.content} />
          )}
          {message.citations.length > 0 && (
            <details className="message-sources">
              <summary>
                Sources{" "}
                <span className="source-count">{message.citations.length}</span>
              </summary>
              <ul className="mt-1 list-inside list-disc">
                {message.citations.map((citation, index) => (
                  <li key={index}>{citation.filename}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      </div>
    </article>
  );
}

function MarkdownContent({ content }: { content: string }) {
  return (
    <div className="[&>*:first-child]:mt-0 [&>*:last-child]:mb-0">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          p: ({ children }) => (
            <p className="my-2 leading-relaxed">{children}</p>
          ),
          strong: ({ children }) => (
            <strong className="font-semibold">{children}</strong>
          ),
          ul: ({ children }) => (
            <ul className="my-2 list-inside list-disc space-y-1">{children}</ul>
          ),
          ol: ({ children }) => (
            <ol className="my-2 list-inside list-decimal space-y-1">
              {children}
            </ol>
          ),
          li: ({ children }) => <li>{children}</li>,
          h1: ({ children }) => (
            <h1 className="mt-3 mb-2 text-base font-semibold">{children}</h1>
          ),
          h2: ({ children }) => (
            <h2 className="mt-3 mb-2 text-base font-semibold">{children}</h2>
          ),
          h3: ({ children }) => (
            <h3 className="mt-3 mb-1 text-sm font-semibold">{children}</h3>
          ),
          a: ({ children, href }) => (
            <a
              href={href}
              target="_blank"
              rel="noreferrer"
              className="text-blue-600 underline dark:text-blue-400"
            >
              {children}
            </a>
          ),
          code: ({ children }) => (
            <code className="rounded bg-gray-200 px-1 py-0.5 font-mono text-xs dark:bg-gray-700">
              {children}
            </code>
          ),
          pre: ({ children }) => (
            <pre className="my-2 overflow-x-auto rounded bg-gray-200 p-2 font-mono text-xs dark:bg-gray-700">
              {children}
            </pre>
          ),
          table: ({ children }) => (
            <div className="my-2 overflow-x-auto">
              <table className="min-w-full border-collapse text-xs">
                {children}
              </table>
            </div>
          ),
          thead: ({ children }) => (
            <thead className="border-b border-gray-300 dark:border-gray-600">
              {children}
            </thead>
          ),
          tr: ({ children }) => (
            <tr className="border-b border-gray-200 dark:border-gray-700">
              {children}
            </tr>
          ),
          th: ({ children }) => (
            <th className="px-2 py-1 text-left font-semibold">{children}</th>
          ),
          td: ({ children }) => (
            <td className="px-2 py-1 align-top">{children}</td>
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
