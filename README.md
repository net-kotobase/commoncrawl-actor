# kotobase-commoncrawl-actor

A governed Common Crawl ingestion actor for
[net-kotobase](https://kotobase.net) (`ai.gftd.apps.kotobase.web.{ingest,search}`).
Given a small, operator-maintained **seed list** of domains
(`resources/seeds.edn`), this actor periodically (1) looks up the domain's
latest capture in the [Common Crawl](https://commoncrawl.org) CDX index, (2)
fetches and extracts the page text, (3) asks an LLM (`murakumo-main`) to
classify/summarize it and asks a self-hosted embedding model for a vector,
(4) checks the result against an independent Governor, and (5) — only if the
Governor approves — self-authenticates with its own CACAO credential and
ingests the page into net-kotobase, where it becomes immediately
token-searchable (and, once embedded, vector-searchable too) via the
existing `web.search` XRPC route.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime
(portable `.cljc`) — the same actor pattern as
[`gftd-talent-actor`](https://github.com/gftdcojp/gftd-talent-actor) /
[`robotaxi-actor`](https://github.com/com-junkawasaki/robotaxi-actor) /
[`cloud-itonami`](https://github.com/gftdcojp/cloud-itonami).

## Honest scope (read this first)

This is **not** a general web crawler and **not** a proxy to an external
search engine. It is a seed-domain-list-driven ingestion actor:

- It can only ever fetch/ingest domains present in
  `resources/seeds.edn` (`commoncrawl.seeds`) — anything else is a HARD
  Governor violation with no override (`commoncrawl.policy`).
- It ingests a small, bounded number of pages per scheduled *tick*
  (`commoncrawl.loop/default-budget-cap`, default 3) — never a bulk crawl.
  It is meant to be invoked by a scheduler (cron / the `schedule` skill),
  not run as an always-on process.
- Common Crawl's Index API is URL-keyed only — there is no keyword/industry
  discovery. A miss for a configured seed just means that specific monthly
  crawl didn't happen to capture it, not that the domain doesn't exist.

See the parent-workspace ADR,
`90-docs/adr/2607192200-net-kotobase-commoncrawl-web-search-integration.edn`
(in `com-junkawasaki/root`), and this repo's own `docs/adr/` for the full
design rationale — including the retracted predecessor ADR this one
replaces (a prior ADR fabricated a resident actor with this exact shape
that was never actually built; this repo is the real thing).

## Architecture

```
intake -> fetch -> advise -> govern -> decide -> commit | hold
```

- **intake** — pick the next seed (round-robin, driven by `commoncrawl.loop`).
- **fetch** — `commoncrawl.cdx`: CDX index query + WARC range fetch + envelope
  strip. Ports the lessons of `dossier.commoncrawl` (a genuine no-match is
  HTTP 404 **with** a JSON body, not a bare 200 — a naive parser silently
  degrades every miss to "unknown") and `kenchi.commoncrawl` (index-query →
  WARC-range-fetch shape), not their JVM-only code.
- **advise** — `commoncrawl.llm` (category/summary/entities extraction,
  `murakumo-main` alias, following the `70-tools/bmc` `llm-complete-fn`
  convention) + `commoncrawl.embeddings` (murakumo's self-hosted
  `/v1/embeddings`). The contained intelligence node — a proposal only,
  never a commit.
- **govern** — `commoncrawl.policy`: HARD (seed-scope / exclude-list /
  per-tick budget, no override) + SOFT (confidence floor) checks.
- **decide / commit / hold** — a HARD or SOFT violation always HOLDs (no
  approval node in this actor — see `commoncrawl.operation`'s docstring);
  only a clean, in-scope, confident proposal reaches **commit**, which
  self-mints a CACAO (`commoncrawl.identity`) and calls net-kotobase's
  `web.ingest` (`commoncrawl.kotobase`).

Every network/gzip capability (`http-fn`/`warc-fetch-fn`/`complete-fn`/
`embed-fn`) is injected — the entire core above is offline-testable against
canned fixtures. The **only** namespace doing real IO is
`commoncrawl.live-http` (nbb-only, `.cljs`, per this workspace's runtime
priority), used by the live entry point `bin/tick.cljs`.

## Quickstart

```bash
# offline demo (no network, no deps beyond deps.edn's :dev alias)
clojure -M:dev:run

# tests (CDX/WARC, Governor, CACAO identity, kotobase client, LLM/embeddings, StateGraph, store, loop)
clojure -M:dev:test

# lint
clojure -M:lint

# a REAL tick (mints its own CACAO, fetches from Common Crawl, calls murakumo,
# and ingests into production kotobase.net — see docs/operator-guide.md)
COMMONCRAWL_MURAKUMO_TOKEN=<token> \
  nbb --classpath "src:../langgraph/src:../langchain/src:../langchain-store/src:../org-chainagnostic-cacao/src:../org-ietf-ed25519/src:../org-ietf-cbor/src" \
  bin/tick.cljs --budget 1
```

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture,
[`docs/operator-guide.md`](docs/operator-guide.md) for running a real tick
and growing the seed list, and [`docs/business-model.md`](docs/business-model.md)
for how this fits net-kotobase/cloud-itonami's business model.

## Repository ownership

This repository is owned by the GitHub organization `net-kotobase` as `net-kotobase/commoncrawl-actor`. Reusable language and storage contracts remain in `kotoba-lang`.