# Design

## Why an actor layer at all

An LLM extraction step is great at drafting a category/summary/entity list
from raw page text — but it has no notion of *scope* (which domains this
actor is even allowed to touch), *budget* (how much it may fetch per tick),
or *trust* (whether its own output is confident enough to publish). Letting
it write straight to net-kotobase invites exactly the failure this ADR
retracted a fabricated predecessor over: an actor that claims to be
"governed" but isn't actually gated by anything real. This project seals the
LLM into a single `:advise` node and wraps it with an independent
`commoncrawl.policy` Governor and an immutable audit ledger
(`commoncrawl.store`).

## Modules

| ns | responsibility |
|---|---|
| `commoncrawl.seeds` | the configured seed domain list (`resources/seeds.edn` + embedded fallback) |
| `commoncrawl.cdx` | CDX index query + WARC-envelope stripping (pure, injected fetch-fn) |
| `commoncrawl.extract` | minimal HTML → title/text extraction |
| `commoncrawl.llm` | LLM extraction (category/summary/entities/confidence), `murakumo-main` |
| `commoncrawl.embeddings` | murakumo `/v1/embeddings` client (response parsing) |
| `commoncrawl.identity` | CACAO self-mint (Ed25519 seed persistence, `cacao.core/mint`) |
| `commoncrawl.kotobase` | `web.ingest`/`web.search` XRPC client (payload building + parsing) |
| `commoncrawl.policy` | the Governor: seed-scope / exclude / budget (hard), confidence (soft) |
| `commoncrawl.store` | this actor's OWN bookkeeping: ledger, dedupe index, cursor, lease, budget |
| `commoncrawl.operation` | the StateGraph: intake → fetch → advise → govern → decide → commit/hold |
| `commoncrawl.loop` | the durable outer loop: one bounded, budgeted, leased tick |
| `commoncrawl.report` | human-readable ledger/tick rendering |
| `commoncrawl.sim` | offline demo runner |
| `commoncrawl.live-http` | the ONLY ns doing real network/gzip IO (nbb-only, `.cljs`) |

## The injection boundary

Every capability that touches the outside world is an argument, not a
hardcoded call:

- `commoncrawl.operation/build`'s `:advise-fn` / `:embed-fn` / `:fetch-fn` /
  `:warc-fetch-fn` / `:ingest-fn`.
- `commoncrawl.cdx`'s `fetch-fn` / `warc-fetch-fn`.
- `commoncrawl.llm`'s `complete-fn`.
- `commoncrawl.embeddings`'s `embed-fn`.
- `commoncrawl.kotobase`'s `http-fn`.

This is the same "Store / Advisor / Phase are all swaps" discipline
`gftd-talent-actor`/`robotaxi-actor` use, extended one step further: real IO
itself is a swap. `commoncrawl.sim` (offline) and `bin/tick.cljs` (live)
build the exact same graph from `commoncrawl.operation/build` — they only
differ in which functions they pass in.

## Why no approval/interrupt node

`talent.operation` (gftd-talent-actor) has a `:request-approval` node with
`interrupt-before` for a human decision on a soft (escalate) verdict. This
actor doesn't — a wrong category/summary tag on a search result is a much
lower-stakes mistake than an HR action, so both hard and soft policy
violations resolve straight to `:hold` (tagged distinctly via
`commoncrawl.policy/hold-fact`'s `:soft?` field for observability). A future
version could add a human-approval node for the soft path without changing
this contract.

## Durability: what survives across ticks

`commoncrawl.store`'s `Store` protocol holds everything that must survive
between separate `commoncrawl.loop/tick!` invocations: the append-only
decision ledger, a URL dedupe index, the seed round-robin cursor, a
per-tick fetch-budget counter, a best-effort single-runner lease, and a
per-tick summary log. Two backends: `MemStore` (in-process, used by tests/
`commoncrawl.sim`), and `DatomicStore` (`langchain.db`-backed via
`kotoba-lang/langchain-store`'s shared codec/identity-schema/event-stream
helpers — no 191st hand-rolled `enc`/`dec*` pair, ADR-2607141600).
`commoncrawl.store/file-store` adds a third option: a `MemStore` whose atom
persists to a single EDN file across separate OS process invocations — the
practical default for `bin/tick.cljs` without standing up a real
kotoba-server pod behind `DatomicStore`.

**Lease caveat, stated plainly**: the lease is a best-effort single-runner
guard for one process/file, not a distributed lock. It is the mechanism
that lets a crashed tick's slot be reclaimed once its TTL elapses
(`commoncrawl.store`'s `acquire-lease!`), not a guarantee against two
concurrent schedulers racing on the same store.
