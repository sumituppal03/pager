import Link from "next/link";
import { listTriages } from "@/lib/api";

/**
 * Landing page — most recent triages, most recent first.
 *
 * This is a React Server Component: it fetches on the server, streams
 * to the browser, no client-side loading state needed. Fresh data on
 * every request because api.ts uses cache: "no-store".
 */
export default async function TriageListPage() {
  let triages;
  let fetchError: string | null = null;
  try {
    triages = await listTriages(50);
  } catch (e) {
    fetchError = e instanceof Error ? e.message : String(e);
    triages = [];
  }

  return (
    <main className="mx-auto max-w-6xl px-6 py-10">
      <div className="mb-8">
        <h1 className="text-3xl font-semibold tracking-tight text-slate-900">
          Pager
        </h1>
        <p className="mt-1 text-slate-500">
          AI incident response — recent triages
        </p>
      </div>

      {fetchError && (
        <div className="mb-6 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          <div className="font-medium">Could not reach backend</div>
          <div className="mt-1 font-mono text-xs">{fetchError}</div>
          <div className="mt-2 text-red-700">
            Make sure the Java API is running at{" "}
            <code className="rounded bg-red-100 px-1">http://localhost:8080</code>
          </div>
        </div>
      )}

      {triages.length === 0 && !fetchError && (
        <div className="rounded-md border border-slate-200 bg-slate-50 px-4 py-8 text-center text-sm text-slate-500">
          No triages yet. Send a webhook to{" "}
          <code className="rounded bg-slate-100 px-1">/webhooks/pagerduty</code>{" "}
          to create one.
        </div>
      )}

      {triages.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-slate-200 shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 bg-white">
            <thead className="bg-slate-50 text-left text-xs font-medium uppercase tracking-wider text-slate-500">
              <tr>
                <th className="px-4 py-3">Triage</th>
                <th className="px-4 py-3">Alert</th>
                <th className="px-4 py-3">Category</th>
                <th className="px-4 py-3">Confidence</th>
                <th className="px-4 py-3">Decision</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Created</th>
              </tr>
            </thead>   
            <tbody className="divide-y divide-slate-100 text-sm">
              {triages.map((t) => (
                <tr
                  key={t.id}
                  className="hover:bg-slate-50 transition-colors"
                >
                  <td className="px-4 py-3">
                    <Link
                      href={`/triages/${t.id}`}
                      className="font-mono text-xs text-blue-600 hover:underline"
                    >
                      {t.id.slice(0, 20)}
                    </Link>
                  </td>
                  <td className="px-4 py-3 max-w-md truncate text-slate-700">
                    {t.alertSummary}
                  </td>
                  <td className="px-4 py-3">
                    <CategoryBadge category={t.aggregatedCategory} />
                  </td>
                  <td className="px-4 py-3 text-slate-600">
                    {formatConfidence(t.aggregatedConfidence)}
                  </td>
                  <td className="px-4 py-3">
                    <DecisionBadge decision={t.notificationDecision} />
                  </td>
                  <td className="px-4 py-3 text-slate-500">{t.status}</td>
                  <td className="px-4 py-3 text-slate-500 text-xs">
                    {formatTime(t.createdAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

function formatConfidence(c: number | null): string {
  if (c === null) return "—";
  return c.toFixed(2);
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString();
}

function CategoryBadge({ category }: { category: string | null }) {
  if (!category) return <span className="text-slate-400">—</span>;
  const color = category === "unknown"
    ? "bg-slate-100 text-slate-600"
    : "bg-purple-100 text-purple-700";
  return (
    <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${color}`}>
      {category}
    </span>
  );
}

function DecisionBadge({ decision }: { decision: string | null }) {
  if (!decision) return <span className="text-slate-400">—</span>;
  const color = decision === "AUTO_POSTED"
    ? "bg-green-100 text-green-700"
    : decision === "AWAITING_REVIEW"
    ? "bg-amber-100 text-amber-700"
    : "bg-slate-100 text-slate-600";
  return (
    <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${color}`}>
      {decision.replace(/_/g, " ").toLowerCase()}
    </span>
  );
}