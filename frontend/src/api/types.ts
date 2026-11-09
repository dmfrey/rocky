export interface User {
  id: number;
  username: string;
  firstName: string | null;
  lastName: string | null;
  email: string | null;
}

export type AuthMode = "jdbc" | "oidc";

// GET /api/auth/config. loginUrl is a backend *path* (e.g. /oauth2/authorization/sso), set only in
// oidc mode.
export interface AuthConfig {
  mode: AuthMode;
  registrationEnabled: boolean;
  loginUrl: string | null;
}

// POST /api/auth/logout in oidc mode: the IdP end-session URL the browser has to navigate to.
export interface LogoutResult {
  logoutUrl: string;
}

export type DocumentContentType = "PDF" | "DOCX" | "TXT";
export type DocumentStatus = "PROCESSING" | "READY" | "FAILED";

export interface Document {
  id: number;
  filename: string;
  contentType: DocumentContentType;
  status: DocumentStatus;
  errorMessage: string | null;
  chunkCount: number | null;
  uploadedBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ChatSession {
  id: number;
  title: string | null;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
}

export type ChatRole = "USER" | "ASSISTANT";

export interface Citation {
  documentId: number | null;
  filename: string;
}

export interface ChatMessage {
  role: ChatRole;
  content: string;
  citations: Citation[];
}
