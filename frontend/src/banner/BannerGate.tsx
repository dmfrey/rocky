import { AuthCard } from "../components/AuthCard";
import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { bannerApi } from "../api/banner";

const BANNER_QUERY_KEY = ["banner"];

// Wraps the whole app (see main.tsx) - renders nothing but a blocking consent screen until this
// session has acknowledged the banner. The backend is the actual enforcement point (every other
// endpoint 428s pre-acknowledgment - see BannerAckFilter); this component exists to show the
// required text and drive the acknowledgment call, not to gate access on its own. A client that
// skipped this component entirely would just get 428s from every other request.
export function BannerGate({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [ackError, setAckError] = useState<string | null>(null);

  const bannerQuery = useQuery({
    queryKey: BANNER_QUERY_KEY,
    queryFn: bannerApi.get,
  });

  const ackMutation = useMutation({
    mutationFn: bannerApi.acknowledge,
    onSuccess: () =>
      queryClient.setQueryData(BANNER_QUERY_KEY, {
        text: bannerQuery.data?.text ?? "",
        acknowledged: true,
      }),
  });

  if (bannerQuery.isLoading) {
    return (
      <AuthCard title="Connecting to your workspace">
        <p role="status" className="subtle-text">
          Loading the usage notice…
        </p>
      </AuthCard>
    );
  }

  if (bannerQuery.isError) {
    return (
      <AuthCard title="Workspace unavailable">
        <p role="alert" className="alert alert-danger">
          Couldn&apos;t load the consent banner. Please refresh the page to try
          again.
        </p>
      </AuthCard>
    );
  }

  if (bannerQuery.data?.acknowledged) {
    return children;
  }

  async function handleAcknowledge() {
    setAckError(null);
    try {
      await ackMutation.mutateAsync();
    } catch {
      setAckError(
        "Something went wrong recording your acknowledgment. Please try again.",
      );
    }
  }

  return (
    <AuthCard title="Notice and Consent" wide>
      <pre className="consent-text">{bannerQuery.data?.text}</pre>
      <div>
        {ackError && (
          <p role="alert" className="alert alert-danger">
            {ackError}
          </p>
        )}
        <button
          type="button"
          onClick={handleAcknowledge}
          disabled={ackMutation.isPending}
          className="btn btn-primary w-full"
        >
          {ackMutation.isPending ? "Recording…" : "I Agree"}
        </button>
      </div>
    </AuthCard>
  );
}
