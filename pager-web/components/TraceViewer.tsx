"use client";

import type { AgentEventView } from "@/lib/api";

/**
 * Gantt-style trace viewer — one horizontal bar per span, colored by
 * specialist, ordered by start time. LLM calls and errors within a span
 * appear as marker dots on the bar.
 *
 * Rendered as pure SVG with computed positions. No charting library —
 * this is ~200 lines of arithmetic and it looks better than the
 * alternatives.
 */
export function TraceViewer({ events }: { events: AgentEventView[] }) {
  if (events.length === 0) {
    return (
      <div className="rounded-lg border border-slate-200 bg-slate-50 p-6 text-center text-sm text-slate-500">
        No events yet.
      </div>
    );
  }

  // Group events by spanId to compute span durations
  type Span = {
    spanId: string;
    parentSpanId: string | null;
    specialist: string;
    startTs: number;
    endTs: number;
    duration: number;
    outcome: string | null;
    markers: Array<{ ts: number; eventType: string; latencyMs: number | null }>;
  };

  const spanMap = new Map<string, Span>();
  for (const e of events) {
    const ts = new Date(e.ts).getTime();
    let span = spanMap.get(e.spanId);
    if (!span) {
      span = {
        spanId: e.spanId,
        parentSpanId: e.parentSpanId,
        specialist: e.specialist ?? "unknown",
        startTs: ts,
        endTs: ts,
        duration: 0,
        outcome: null,
        markers: [],
      };
      spanMap.set(e.spanId, span);
    }
    if (e.eventType === "span.start") {
      span.startTs = ts;
    } else if (e.eventType === "span.end") {
      span.endTs = ts;
      span.duration = e.latencyMs ?? (ts - span.startTs);
      span.outcome = e.outcome;
    } else {
      span.markers.push({
        ts,
        eventType: e.eventType,
        latencyMs: e.latencyMs,
      });
    }
  }

  const spans = Array.from(spanMap.values()).sort(
    (a, b) => a.startTs - b.startTs
  );

  // Compute time bounds across all events (not just spans, so markers
  // land in the right place even when a span is still open)
  const allTimes = events.map((e) => new Date(e.ts).getTime());
  const traceStart = Math.min(...allTimes);
  const traceEnd = Math.max(...allTimes);
  const traceDuration = Math.max(1, traceEnd - traceStart);

  const rowHeight = 32;
  const chartWidth = 800;
  const leftLabelWidth = 120;
  const rightPadding = 20;
  const barWidth = chartWidth - leftLabelWidth - rightPadding;
  const chartHeight = spans.length * rowHeight + 40;

  const timeToX = (ts: number): number => {
    const frac = (ts - traceStart) / traceDuration;
    return leftLabelWidth + frac * barWidth;
  };

  const specialistColor = (specialist: string): string => {
    const colors: Record<string, string> = {
      symptoms: "#3b82f6",
      change: "#f97316",
      metrics: "#14b8a6",
      comms: "#ec4899",
      aggregator: "#6366f1",
    };
    return colors[specialist] ?? "#94a3b8";
  };

  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <svg width={chartWidth} height={chartHeight} className="text-slate-700">
        {/* Time axis */}
        <line
          x1={leftLabelWidth}
          y1={20}
          x2={chartWidth - rightPadding}
          y2={20}
          stroke="#e2e8f0"
          strokeWidth={1}
        />
        <text x={leftLabelWidth} y={14} className="fill-slate-500" fontSize="10">
          0ms
        </text>
        <text
          x={chartWidth - rightPadding}
          y={14}
          textAnchor="end"
          className="fill-slate-500"
          fontSize="10"
        >
          {Math.round(traceDuration)}ms
        </text>

        {/* Spans */}
        {spans.map((span, i) => {
          const x1 = timeToX(span.startTs);
          const x2 = timeToX(span.endTs);
          const width = Math.max(2, x2 - x1);
          const y = 30 + i * rowHeight;
          const barColor = span.outcome === "error"
            ? "#ef4444"
            : specialistColor(span.specialist);
          const opacity = span.outcome === "error" ? 0.4 : 0.9;

          return (
            <g key={span.spanId}>
              {/* Label */}
              <text
                x={leftLabelWidth - 8}
                y={y + 18}
                textAnchor="end"
                fontSize="11"
                className="fill-slate-700 font-mono"
              >
                {span.specialist}
              </text>
              {/* Bar */}
              <rect
                x={x1}
                y={y + 4}
                width={width}
                height={20}
                fill={barColor}
                opacity={opacity}
                rx={2}
              />
              {/* Duration label */}
              <text
                x={x2 + 4}
                y={y + 18}
                fontSize="10"
                className="fill-slate-500"
              >
                {Math.round(span.duration)}ms
              </text>
              {/* Markers (llm.call, error, etc.) */}
              {span.markers.map((m, mi) => {
                const mx = timeToX(m.ts);
                const isError = m.eventType === "error";
                return (
                  <circle
                    key={mi}
                    cx={mx}
                    cy={y + 14}
                    r={3}
                    fill={isError ? "#ef4444" : "#fbbf24"}
                    stroke="white"
                    strokeWidth={1}
                  >
                    <title>{m.eventType}{m.latencyMs ? ` (${m.latencyMs}ms)` : ""}</title>
                  </circle>
                );
              })}
            </g>
          );
        })}
      </svg>

      {/* Legend */}
      <div className="mt-4 flex flex-wrap gap-4 text-xs text-slate-500">
        <div className="flex items-center gap-1">
          <div className="h-2 w-2 rounded-full bg-amber-400" />
          <span>llm.call</span>
        </div>
        <div className="flex items-center gap-1">
          <div className="h-2 w-2 rounded-full bg-red-500" />
          <span>error</span>
        </div>
      </div>
    </div>
  );
}