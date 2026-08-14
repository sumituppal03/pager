/**
 * Frontend API client for the Pager Java backend.
 *
 * Kept small and typed. All fetches go through `apiFetch` which handles
 * the base URL and error mapping. Response types mirror the Java DTOs
 * in TriageListView and TriageDetailView.
 */

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

// ---- Response shapes (mirror the Java DTOs) ----

export type TriageListView = {
  id: string;
  incidentId: string;
  alertSummary: string;
  service: string | null;
  severity: string | null;
  status: string | null;
  aggregatedCategory: string | null;
  aggregatedConfidence: number | null;
  aggregatedSummary: string | null;
  notificationDecision: string | null;
  createdAt: string;
  completedAt: string | null;
};

export type FindingView = {
  id: string;
  specialist: string | null;
  category: string | null;
  severity: string | null;
  confidence: number | null;
  summary: string;
  rationale: string;
  createdAt: string;
};

export type NotificationView = {
  id: string;
  decision: string;
  channel: string;
  payload: string;
  createdAt: string;
};

export type AgentEventView = {
  id: string;
  ts: string;
  eventType: string;
  specialist: string | null;
  spanId: string;
  parentSpanId: string | null;
  model: string | null;
  tokensIn: number | null;
  tokensOut: number | null;
  latencyMs: number | null;
  outcome: string | null;
};

export type TriageDetailView = {
  id: string;
  incidentId: string;
  alertSummary: string;
  service: string | null;
  severity: string | null;
  status: string | null;
  aggregatedSummary: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  findings: FindingView[];
  notification: NotificationView | null;
  events: AgentEventView[];
};

// ---- Fetch helpers ----

async function apiFetch<T>(path: string): Promise<T> {
  const url = `${API_BASE}${path}`;
  // Next.js server components cache by default; we want fresh triages
  // every render since the DB changes constantly during a demo.
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) {
    throw new Error(`GET ${path} failed: ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
}

export async function listTriages(limit = 50): Promise<TriageListView[]> {
  return apiFetch<TriageListView[]>(`/api/triages?limit=${limit}`);
}

export async function getTriage(id: string): Promise<TriageDetailView> {
  return apiFetch<TriageDetailView>(`/api/triages/${id}`);
}