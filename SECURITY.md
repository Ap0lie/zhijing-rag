# Security Policy

## Supported Versions

Zhijing RAG is under active development. Until the first tagged release,
security fixes target only the latest commit on `main`.

| Target | Supported |
| --- | --- |
| Latest `main` | Yes |
| Older commits, forks, and local modifications | No |

## Reporting a Vulnerability

Use GitHub Private Vulnerability Reporting:

https://github.com/Ap0lie/zhijing-rag/security/advisories/new

Do not open a public issue for a suspected vulnerability.

Include:

- the affected commit, configuration, and component;
- prerequisites and minimal reproduction steps;
- the expected and observed security boundary;
- realistic impact and reachability;
- sanitized logs or a minimal proof of concept when useful.

Do not submit credentials, API keys, private documents, user conversations,
memory content, database dumps, or other production data.

The project is maintained on a best-effort basis and currently offers no
response-time SLA or bug bounty. Please keep report details private until a
fix and coordinated disclosure plan are available.

## System and Scope

This policy covers the source and configuration in:

- `backend/`: Spring Boot API, persistence, parsers, Java workers, retrieval,
  GraphRAG, memory, and evaluation;
- `frontend/`: React application and Nginx configuration;
- `worker/`: Python community detection service;
- `mineru/`: optional MinerU integration;
- `compose.yaml` and repository automation.

Security-sensitive assets include user accounts and sessions, document
contents, ACLs, immutable revisions, source locations, citations, memory,
evaluation data, object storage, model credentials, and publication state.

## Threat Model and Trust Boundaries

Relevant attackers include unauthenticated clients, authenticated users
attempting cross-user access, malicious document uploaders, untrusted model
or provider responses, and interrupted or stale workers.

Administrators are trusted operators, but privileged mutations must still
enforce authentication, CSRF protection, confirmation, version checks, and
audit requirements.

PostgreSQL is the system of record for authorization and publication facts.
OpenSearch, graph projections, caches, and model output are derived or
untrusted inputs and cannot independently authorize access.

## Security Invariants

- `/api/v1/admin/**` remains restricted to administrators.
- State-changing browser requests remain protected by authentication and
  CSRF validation.
- Document, Chunk, Evidence, Citation, Graph, Global, and Memory access is
  revalidated against the current user, ACL, and Revision.
- Authorization or database recheck failures fail closed.
- MinIO objects remain private and are served only through an authorized
  Backend path.
- Revoked, deleted, stale, or old-Revision content cannot reappear through
  OpenSearch, GraphRAG, caches, history, memory, or citations.
- Parser inputs remain bounded against archive bombs, XXE, external resource
  access, formula execution, malicious HTML, and resource exhaustion.
- Worker leases, fencing tokens, idempotency, and compare-and-set checks
  prevent stale workers from committing late results.
- Evidence or personal memory is sent to a remote model only after the
  corresponding explicit operator opt-in.
- Secrets and unauthorized content do not enter logs, metrics, traces,
  browser errors, or public artifacts.

## Reportable Findings

Examples include:

- authentication, authorization, CSRF, or privilege-escalation bypasses;
- cross-user or revoked-content disclosure;
- command injection, SQL injection, path traversal, SSRF, XXE, unsafe
  deserialization, or remote code execution;
- malicious document processing that bypasses configured safety limits;
- stale-worker or concurrency flaws that corrupt immutable facts or
  publication state;
- secret exposure or unauthorized transmission to remote model providers;
- deletion, rollback, or publication actions that bypass required
  confirmation and version checks.

Severity depends on realistic reachability and the impact on confidentiality,
integrity, availability, or administrative control.

## Out of Scope

- answer quality, hallucination, or retrieval relevance without a security
  boundary violation;
- vulnerabilities solely in an upstream dependency with no reachable,
  project-specific impact;
- self-XSS or social-engineering-only reports;
- resource exhaustion that requires an administrator to remove documented
  limits or deliberately expose the local-development Compose configuration;
- automated scanner output without a reproducible security impact.

Do not test against systems or data you do not own, and do not perform
destructive testing.

## Known Limitations

The default Compose configuration is for local development and evaluation.
It uses HTTP, development credentials, `SESSION_COOKIE_SECURE=false`, and an
OpenSearch instance without its Security Plugin. It must not be exposed
directly to an untrusted network.

Sessions are stored in Backend process memory, so the current configuration
is not suitable for unmodified horizontal scaling or high-availability
deployment. Operators are responsible for TLS, secret management, network
isolation, backups, and security review of optional external providers.
