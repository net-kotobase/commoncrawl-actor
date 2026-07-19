# Operator guide

## Running a real tick

`bin/tick.cljs` is the live entry point — it mints/reuses this actor's own
CACAO identity, fetches from Common Crawl, calls murakumo for extraction +
embeddings, and ingests into **production** `kotobase.net`. Run it from a
workspace where the sibling `.cljc` deps are checked out next to this repo
(the standard west layout, `orgs/kotoba-lang/*`):

```bash
COMMONCRAWL_MURAKUMO_TOKEN=<murakumo /v1/messages+/v1/embeddings bearer token> \
  nbb --classpath "src:../langgraph/src:../langchain/src:../langchain-store/src:../org-chainagnostic-cacao/src:../org-ietf-ed25519/src:../org-ietf-cbor/src" \
  bin/tick.cljs --budget 1
```

Flags:

- `--budget N` — max seeds attempted this tick (default 3,
  `commoncrawl.loop/default-budget-cap`). Keep this small.
- `--store PATH` — where the durable state file lives (default
  `.commoncrawl/store.edn`, gitignored).
- `--db-name NAME` — the net-kotobase tenant database name pages are
  ingested into (default `webpages`, matching `web.ingest`'s own default).

The first run creates `.commoncrawl/identity.edn` (a fresh Ed25519 seed +
its derived `did:key`) — **never commit this file or this directory.**
Subsequent runs reuse the same identity/DID.

### Auth token

`COMMONCRAWL_MURAKUMO_TOKEN` authenticates against `api.murakumo.cloud`'s
`/v1/messages` and `/v1/embeddings` (both gated by the same bearer-token
check). Skill `secrets-location-map` in the parent workspace documents this
as kagi item `MURAKUMO_CRITIC_TOKEN` (compartment `gftdcojp`) or 1Password
`gftd.murakumo/ANTHROPIC_PROXY_TOKEN` — as of this writing the kagi item was
**not actually present** in the local vault this repo's initial
implementation session had access to (1Password required interactive auth
this session couldn't perform either), so the LLM-extraction/embeddings
leg was not live-verified end-to-end — see "Live verification status"
below. If you have `bin/kagi` available and the item exists in your vault,
set `KAGI_BIN=/path/to/kagi` and `commoncrawl.live-http` will fetch the
token itself.

## Live verification status (2026-07-19, initial implementation)

What was actually proven against PRODUCTION services in this session
(not offline fixtures):

- ✅ **Common Crawl CDX index query** — real `GET
  index.commoncrawl.org/CC-MAIN-2026-25-index?url=...` against
  `https://www.sec.gov/`, 113 real captures returned.
- ✅ **WARC range fetch + gunzip + envelope strip** — a real ranged `GET`
  against `data.commoncrawl.org`, real gzip decompression, 223,075
  characters of real page text extracted (this fetch ALSO found and fixed
  a real bug: the newest capture by timestamp was a contentless 301
  redirect stub, not a 200 response — see `commoncrawl.cdx/
  latest-response-capture`'s docstring and its regression tests).
- ✅ **CACAO self-mint** — a real Ed25519 identity, a real signed CACAO
  (`cacao.core/mint`), independently `cacao.core/verify`-checked.
- ✅ **`web.ingest` against production `kotobase.net`** — a real POST,
  `{:ok true :status 200 :datom_count 5}`.
- ✅ **`web.search` against production `kotobase.net`** — a real POST
  confirming the just-ingested page is now findable (score 10.21, correct
  url/title/snippet).
- ⚠️ **NOT live-verified**: the `:advise` step's `murakumo-main` LLM
  extraction and the `/v1/embeddings` call — no working bearer token was
  obtainable non-interactively in that session (see "Auth token" above).
  The ingested test page therefore carried no `extracted_category`/
  `extracted_summary`/`extracted_entities`/`embedding` fields (all
  optional/additive per the lexicon, so the ingest itself still succeeded
  honestly, just without that enrichment). Both legs ARE covered by the
  offline test suite (`test/commoncrawl/llm_test.cljc`,
  `test/commoncrawl/embeddings_test.cljc`) and the nbb smoke test
  (`test/nbb_smoke.cljs`) against mocked responses — re-run
  `bin/tick.cljs` with a working token to complete this leg's live
  verification.

## Growing the seed list

Add an entry to `resources/seeds.edn`:

```edn
{:domain "www.example.com" :url "https://www.example.com/" :label "why this domain is seeded"}
```

That's the entire scope-widening operation — no code change. Removing a
domain (or adding it to an exclude set passed to `commoncrawl.loop/tick!`'s
`:exclude` opt) immediately blocks it again via `commoncrawl.policy`'s hard
seed-scope/exclude checks.

## Scheduling

This actor is designed to be invoked by a scheduler, not run as an
always-on process — e.g. the `schedule` skill's cron-based routine, or any
external cron calling `bin/tick.cljs`. A reasonable starting cadence is
hourly or daily with `--budget 1`–`3`.

## Reviewing what happened

```bash
clojure -M:dev:run  # for a quick illustrative ledger (offline)
```

Or, against the real store file:

```clojure
(require '[commoncrawl.store :as store] '[commoncrawl.report :as report])
(def st (store/file-store ".commoncrawl/store.edn"))
(println (report/render-ledger (store/ledger st)))
(println (report/render-ticks (store/tick-log st)))
```
