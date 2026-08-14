import Link from "next/link";
import { notFound } from "next/navigation";
import { getTriage } from "@/lib/api";
import { TraceViewer } from "@/components/TraceViewer";

export default async function TriageDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  let triage;
  try {
    triage = await getTriage(id);
  } catch (e) {
    notFound();
  }

  return (
    <main className="mx-auto max-w-6xl px-6 py-10">
      <Link
        href="/"
        className="mb-6 inline-flex items-center text-sm text-blue-600 hover:underline"
      >
        ← All triages
      </Link>

      <div className="mb-8">
        <h1 className="font-mono text-lg text-slate-900">{triage.id}</h1>
        <p className="mt-2 text-slate-700">{triage.alertSummary}</p>
        <div className="mt-3 flex flex-wrap gap-4 text-sm text-slate-500">
          {triage.service && <span>service: {triage.service}</span>}
          {triage.severity && <span>severity: {triage.severity}</span>}
          {triage.status && <span>status: {triage.status}</span>}
          {triage.incidentId && <span>incident: {triage.incidentId}</span>}
        </div>
      </div>

      {triage.aggregatedSummary && (
        <section className="mb-8 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-2 text-xs font-medium uppercase tracking-wider text-slate-500">
            Aggregated summary
          </h2>
          <p className="text-slate-800">{triage.aggregatedSummary}</p>
        </section>
      )}

      {triage.notification && (
        <section className="mb-8 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-3 text-xs font-medium uppercase tracking-wider text-slate-500">
            Notification
          </h2>
          <div className="flex items-center gap-2 mb-3">
            <NotificationBadge decision={triage.notification.decision} />
            <span className="text-xs text-slate-500">
              via {triage.notification.channel}
            </span>
          </div>
          <pre className="whitespace-pre-wrap rounded bg-slate-50 p-3 font-mono text-xs text-slate-700">
            {triage.notification.payload}
          </pre>
        </section>
      )}

      <section className="mb-8">
        <h2 className="mb-3 text-xs font-medium uppercase tracking-wider text-slate-500">
          Findings ({triage.findings.length})
        </h2>
        <div className="space-y-3">
          {triage.findings.map((f) => (
            <div
              key={f.id}
              className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
            >
              <div className="mb-2 flex items-center gap-3">
                <SpecialistBadge specialist={f.specialist} />
                {f.category && f.category !== "unknown" && (
                  <span className="rounded-full bg-purple-100 px-2 py-0.5 text-xs font-medium text-purple-700">
                    {f.category}
                  </span>
                )}
                <span className="text-xs text-slate-500">
                  confidence: {f.confidence?.toFixed(2) ?? "—"}
                </span>
              </div>
              <p className="text-sm text-slate-800">{f.summary}</p>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-xs font-medium uppercase tracking-wider text-slate-500">
          Trace ({triage.events.length} events)
        </h2>
        <TraceViewer events={triage.events} />
      </section>
    </main>
  );
}

function SpecialistBadge({ specialist }: { specialist: string | null }) {
  if (!specialist) return null;
  const colors: Record<string, string> = {
    symptoms: "bg-blue-100 text-blue-700",
    change: "bg-orange-100 text-orange-700",
    metrics: "bg-teal-100 text-teal-700",
    comms: "bg-pink-100 text-pink-700",
    aggregator: "bg-indigo-100 text-indigo-700",
  };
  const color = colors[specialist] ?? "bg-slate-100 text-slate-700";
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${color}`}>
      {specialist}
    </span>
  );
}

function NotificationBadge({ decision }: { decision: string }) {
  const color =
    decision === "auto_posted"
      ? "bg-green-100 text-green-700"
      : decision === "awaiting_review"
      ? "bg-amber-100 text-amber-700"
      : "bg-slate-100 text-slate-600";
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${color}`}>
      {decision.replace(/_/g, " ")}
    </span>
  );
}