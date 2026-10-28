import type { ReactNode } from "react";
import { RockyMark } from "./Icon";

export function AuthCard({
  title,
  children,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  wide?: boolean;
}) {
  return (
    <div className="auth-layout">
      <header className="product-header">
        <div className="product-brand">
          <RockyMark />
          <span>Rocky</span>
        </div>
        <span className="product-description">Knowledge workspace</span>
      </header>
      <main className="auth-main">
        <div className={`auth-card ${wide ? "consent-card" : ""}`}>
          <p className="eyebrow">ROCKY KNOWLEDGE WORKSPACE</p>
          <h1>{title}</h1>
          {children}
        </div>
      </main>
      <footer className="auth-footer">
        Answers grounded in your documents.
      </footer>
    </div>
  );
}
