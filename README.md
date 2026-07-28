# Pager

**An AI Incident Response Agent — production-grade multi-agent architecture, built end-to-end.**

> When a PagerDuty alert fires at 3 AM, Pager fans four grounded specialists across your observability stack — symptoms, change, metrics, comms — aggregates their findings with confidence scores, and posts a structured triage report to Slack. Every action is written to an immutable events spine so the post-mortem can replay exactly what the agent knew and when.

The repo is two things at once:

1. **A first-principles architecture study** ([read it →](./docs/architecture.html))
   ~10K words deriving the design from first principles: how a senior SRE actually diagnoses at 3 AM, why one Postgres beats three databases, why read-only actions can post autonomously but write-actions never do, and the 20-phase build lifecycle that turns the design into production code.

2. **A working Java + Spring Boot reference implementation**
   Built with the exact stack most enterprise-grade production systems actually use — Java 21, Spring Boot 3, LangChain4j, PostgreSQL + pgvector, Redis, Docker. Deployed via GitHub Actions.

Built by [Sumit Uppal](https://github.com/sumituppal) in Bengaluru.

---

## Why Java + LangChain4j, not Python

95% of AI-agent tutorials are Python. But:
- Half the Fortune 500 runs Java in production
- Every serious fintech, insurance, and enterprise SaaS in India runs on the JVM
- LangChain4j exists, is production-ready, and is almost entirely uncovered in first-principles writeups

Pager is designed to be the reference. If you run Java in production and want to introduce agentic AI systems without adopting a Python stack, this is the shape.

## The design in one paragraph

A PagerDuty webhook triggers a Spring Boot ingress handler that validates HMAC, deduplicates on `incident_id + payload_hash`, and enqueues a triage job to Redis. A worker dequeues the job and fans it out via LangChain4j to four specialist agents in parallel: `symptoms` (what's breaking), `change` (what deployed / flipped recently), `metrics` (what the numbers say), and `comms` (what to tell users and the team). Each specialist is grounded in retrieval over past post-mortems and runbooks (pgvector semantic + full-text search, merged by reciprocal-rank fusion) and in **read-only** tool calls into live observability systems. An aggregator merges the findings, deduplicates across specialists (boosting confidence when they agree), and routes the result through a two-layer HITL gate: read-only analysis posts autonomously when overall confidence ≥ 0.75; suggested write-actions (rollback, restart, scale) are surfaced but **never** executed without human approval. Every action along the way is written to one Postgres `agent_events` table that powers the trace viewer, the audit trail, and the cost ledger.

## Architecture at a glance

```
   PagerDuty ──► Spring @RestController ──► Redis queue ──► Worker
                                                              │
                                                              ▼
                                       ┌────────────────────────────────┐
                                       │    Triage Graph (LangChain4j)  │
                                       │                                 │
                                       │   symptoms  change              │
                                       │       │       │                 │
                                       │   metrics  comms                │
                                       │       │       │                 │
                                       │       └───┬───┘                 │
                                       │           ▼                     │
                                       │       aggregator                │
                                       │           │                     │
                                       │           ▼                     │
                                       │       HITL gate                 │
                                       └───────────┬─────────────────────┘
                                                   │
                                 read-only ────────┼──────── write action
                                       │                          │
                                       ▼                          ▼
                                    Slack                  approval queue
                                                                  │
                                                                  ▼
                                                          human approves
                                                                  │
                                                                  ▼
                                                        execute + audit

  ─────────────── beneath all of it ───────────────
     Postgres + pgvector
     · knowledge_chunks    (memory / RAG)
     · triage_runs         (truth)
     · findings            (truth)
     · hitl_approvals      (truth)
     · agent_events        (time / append-only spine)
  ──────────────────────────────────────────────────
```

For the full first-principles derivation of why this shape and not another, [read the study](./docs/architecture.html).

## Stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 (LTS) | Modern JVM: records, pattern matching, virtual threads |
| Framework | Spring Boot 3.4 | Production-standard, ecosystem you cannot beat |
| AI orchestration | LangChain4j 0.36 | Native JVM, structured output, tool-calling, provider-agnostic |
| LLM providers | OpenAI + Anthropic | Provider routing per specialist |
| Database | PostgreSQL 16 + pgvector | Truth + memory + time in one store — see Part II of the study |
| ORM | Spring Data JPA + Hibernate | Type-safe; Flyway owns schema |
| Queue | Redis (Spring Data Redis) | Async worker, retries, dead-letter |
| Resilience | Resilience4j | Retries, circuit breakers, timeouts |
| Frontend | Next.js 15 + TypeScript | Dashboard + trace viewer (PR #13) |
| Container | Docker + Docker Compose | Same image dev → prod |
| CI/CD | GitHub Actions | Type check, test, build on every PR |

## Quick start (local dev)

You need: Docker Desktop, JDK 21, Maven (or use `./mvnw`).

```bash
# 1. Clone
git clone https://github.com/sumituppal/pager.git
cd pager

# 2. Bring up Postgres + Redis
docker compose up -d

# 3. Copy env template (defaults work for local dev)
cp .env.example .env.local

# 4. Run tests (proves the wiring)
cd pager-api && ./mvnw test

# 5. Run the app
./mvnw spring-boot:run

# 6. Sanity check the health endpoint
curl http://localhost:8080/actuator/health
# → {"status":"UP"}
```

## Status

The current commit is the working baseline: Spring Boot boots, health endpoint
responds, Docker Compose brings up Postgres (with pgvector) + Redis. Everything
after this ships as issues → PRs, following the discipline in
[`CONTRIBUTING.md`](./CONTRIBUTING.md).

Track progress on the [issues page](https://github.com/sumituppal/pager/issues)
and [project board](https://github.com/sumituppal/pager/projects).

## Repo layout

```
pager-api/                       # Spring Boot backend (this PR)
├── src/main/java/dev/sumituppal/pager/
│   ├── PagerApplication.java
│   ├── domain/                  # PR #02: entities, repositories
│   ├── ingress/                 # PR #03: webhook, HMAC, idempotency
│   ├── queue/                   # PR #03: Redis-backed job queue
│   ├── worker/                  # PR #04: consumer, orchestrator
│   ├── specialists/             # PR #07-09: symptoms, change, metrics, comms
│   ├── aggregator/              # PR #09
│   ├── hitl/                    # PR #10
│   ├── memory/                  # PR #11: pgvector RAG + hybrid retrieval
│   ├── tools/                   # PR #12: read-only tool registry
│   ├── observability/           # PR #05: EventEmitter, tracing
│   └── llm/                     # PR #06: LangChain4j wiring
└── src/main/resources/
    ├── application.yml
    └── db/migration/            # Flyway SQL migrations

pager-web/                       # Next.js + TypeScript frontend (PR #13)

docs/
├── architecture.html            # The first-principles study
└── decisions/                   # ADRs added as we build

docker-compose.yml               # Postgres (pgvector) + Redis for local dev
```

## Contributing / feedback

This is a portfolio piece — but I'd love architectural feedback. Open an issue.

If you're a founder or engineering lead at an AI-native or Java-heavy startup and this shape of thinking is what you'd want in a founding / senior engineer, my inbox is very open.

## License

MIT.
