import { Icon } from "../components/Icon";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { documentsApi } from "../api/documents";
import { ApiError } from "../api/client";
import { StatusBadge } from "../components/StatusBadge";
import { ConfirmDialog } from "../components/ConfirmDialog";
import type { Document } from "../api/types";

const MAX_CONCURRENT_UPLOADS = 10;
const ACCEPTED_EXTENSIONS = [
  ".pdf",
  ".doc",
  ".docx",
  ".txt",
  ".png",
  ".jpg",
  ".jpeg",
  ".gif",
];

interface PendingConfirmation {
  title: string;
  message: string;
  confirmLabel: string;
  onConfirm: () => void;
}

function hasAcceptedExtension(filename: string): boolean {
  return ACCEPTED_EXTENSIONS.some((extension) =>
    filename.toLowerCase().endsWith(extension),
  );
}

export function DocumentsPage() {
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDraggingOver, setIsDraggingOver] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [pendingConfirmation, setPendingConfirmation] =
    useState<PendingConfirmation | null>(null);

  const documentsQuery = useQuery({
    queryKey: ["documents"],
    queryFn: documentsApi.list,
    // Keep polling only while something is still being ingested.
    refetchInterval: (query) =>
      query.state.data?.some((doc) => doc.status === "PROCESSING")
        ? 2000
        : false,
    // Ingestion can run 15-20+ minutes for a large document - without this, TanStack Query's
    // default (pause polling whenever the tab isn't the focused/visible one, per the Page
    // Visibility API) means switching away from the tab during that window silently freezes the
    // status column at PROCESSING until a manual reload, even though the backend finished long
    // ago.
    refetchIntervalInBackground: true,
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => documentsApi.upload(file),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["documents"] }),
    onError: (error) =>
      setUploadError(
        error instanceof ApiError
          ? error.message
          : "Upload failed. Please try again.",
      ),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => documentsApi.remove(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["documents"] }),
  });

  const documents = documentsQuery.data ?? [];
  const inFlightCount = documents.filter(
    (doc) => doc.status === "PROCESSING",
  ).length;
  const uploadsBlocked = inFlightCount >= MAX_CONCURRENT_UPLOADS;

  function handleFiles(files: FileList | null) {
    setUploadError(null);
    const file = files?.[0];
    if (!file) return;

    if (!hasAcceptedExtension(file.name)) {
      setUploadError(
        "Only PDF, Word (.doc, .docx), text (.txt), and image (.png, .jpg, .gif) files are supported.",
      );
      return;
    }

    const existing = documents.find((doc) => doc.filename === file.name);
    if (existing) {
      setPendingConfirmation({
        title: "Replace document?",
        message: `This will replace the existing "${existing.filename}" — continue?`,
        confirmLabel: "Replace",
        onConfirm: () => uploadMutation.mutate(file),
      });
      return;
    }

    uploadMutation.mutate(file);
  }

  function handleDelete(doc: Document) {
    setPendingConfirmation({
      title: "Delete document?",
      message: `Delete "${doc.filename}"? This can't be undone.`,
      confirmLabel: "Delete",
      onConfirm: () => deleteMutation.mutate(doc.id),
    });
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <h1>Documents</h1>
          <p>
            Manage the shared knowledge base used to ground your conversations.
          </p>
        </div>
      </div>

      <div
        data-testid="dropzone"
        onDragOver={(event) => {
          event.preventDefault();
          setIsDraggingOver(true);
        }}
        onDragLeave={() => setIsDraggingOver(false)}
        onDrop={(event) => {
          event.preventDefault();
          setIsDraggingOver(false);
          if (!uploadsBlocked) handleFiles(event.dataTransfer.files);
        }}
        className={`upload-area ${isDraggingOver ? "is-dragging" : ""} ${uploadsBlocked ? "is-disabled" : ""}`}
      >
        <Icon name="upload" size={28} />
        <p>Drag and drop a PDF, Word, or text file here, or</p>
        <button
          type="button"
          disabled={uploadsBlocked}
          onClick={() => fileInputRef.current?.click()}
          className="btn"
        >
          Browse files
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept={ACCEPTED_EXTENSIONS.join(",")}
          className="hidden"
          onChange={(event) => {
            handleFiles(event.target.files);
            event.target.value = "";
          }}
        />
        <p>
          Up to 50 MB per file. Images and scanned PDFs require a configured
          vision model.
        </p>
        {uploadsBlocked && (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            {MAX_CONCURRENT_UPLOADS} uploads are already processing — wait for
            one to finish before adding more.
          </p>
        )}
        {uploadError && (
          <p role="alert" className="error-text">
            {uploadError}
          </p>
        )}
      </div>

      <section className="panel">
        <div className="panel-heading">
          <h2>Knowledge base</h2>
          <span className="subtle-text">
            {documents.length} document{documents.length === 1 ? "" : "s"}
          </span>
        </div>
        <div className="table-scroll">
          <table className="data-table" aria-label="Knowledge base documents">
            <thead>
              <tr className="border-b border-gray-200 text-xs text-gray-500 dark:border-gray-800 dark:text-gray-400">
                <th className="py-2 font-medium">Filename</th>
                <th className="py-2 font-medium">Status</th>
                <th className="py-2 font-medium">Uploaded by</th>
                <th className="py-2 font-medium">Updated</th>
                <th className="py-2 font-medium">Chunks</th>
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {documents.map((doc) => (
                <tr
                  key={doc.id}
                  className="border-b border-gray-100 dark:border-gray-900"
                >
                  <td>
                    <div className="document-name">
                      <Icon name="documents" size={18} />
                      {doc.filename}
                    </div>
                  </td>
                  <td className="py-2 pr-4">
                    <StatusBadge status={doc.status} />
                    {doc.status === "FAILED" && doc.errorMessage && (
                      <p className="mt-1 text-xs text-red-600 dark:text-red-400">
                        {doc.errorMessage}
                      </p>
                    )}
                  </td>
                  <td className="py-2 pr-4 text-gray-600 dark:text-gray-400">
                    {doc.uploadedBy}
                  </td>
                  <td className="py-2 pr-4 text-gray-600 dark:text-gray-400">
                    {new Date(doc.updatedAt).toLocaleString()}
                  </td>
                  <td className="py-2 pr-4 text-gray-600 dark:text-gray-400">
                    {doc.chunkCount ?? "—"}
                  </td>
                  <td className="py-2 text-right">
                    <button
                      type="button"
                      onClick={() => handleDelete(doc)}
                      className="error-text hover:underline"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
              {documents.length === 0 && (
                <tr>
                  <td
                    colSpan={6}
                    className="py-8 text-center text-gray-500 dark:text-gray-400"
                  >
                    {documentsQuery.isLoading
                      ? "Loading documents…"
                      : documentsQuery.isError
                        ? "Could not load documents. Please refresh to try again."
                        : "No documents yet — upload one to get started."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {deleteMutation.isError && (
        <p role="alert" className="alert alert-danger">
          Could not delete the document. Please try again.
        </p>
      )}
      {pendingConfirmation && (
        <ConfirmDialog
          title={pendingConfirmation.title}
          message={pendingConfirmation.message}
          confirmLabel={pendingConfirmation.confirmLabel}
          onConfirm={() => {
            pendingConfirmation.onConfirm();
            setPendingConfirmation(null);
          }}
          onCancel={() => setPendingConfirmation(null)}
        />
      )}
    </div>
  );
}
