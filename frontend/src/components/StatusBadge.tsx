import type { DocumentStatus } from "../api/types";
import { Icon } from "./Icon";
const LABELS: Record<DocumentStatus, string> = {
  PROCESSING: "Processing",
  READY: "Ready",
  FAILED: "Failed",
};
export function StatusBadge({ status }: { status: DocumentStatus }) {
  return (
    <span className={`status-label status-${status.toLowerCase()}`}>
      <Icon
        name={
          status === "READY" ? "check" : status === "FAILED" ? "info" : "more"
        }
        size={12}
      />
      {LABELS[status]}
    </span>
  );
}
