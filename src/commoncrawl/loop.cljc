(ns commoncrawl.loop
  "The durable OUTER loop — NOT an always-on process. This actor is meant
  to be invoked by a scheduled routine (cron / claude-code `schedule`
  skill / an equivalent BMC-style 'tick' driver, ADR-2607192200: 'a
  durable outer loop (tick/lease/budget/crash-recovery), not an
  always-on process'). Each call to `tick!` is ONE bounded, budgeted pass
  over (a slice of) the seed list — never an unbounded inner loop.

  State that must survive ACROSS separate `tick!` invocations lives in
  `store` (see `commoncrawl.store`'s `:agent.loop/*` / `:agent.tick/*` /
  `:agent.lease/*` / `:agent.budget/*` families), not in this process's
  memory:

    - `:agent.loop/cursor`  — round-robin position in the seed list, so
      consecutive ticks cover DIFFERENT seeds rather than always
      re-attempting the first `budget-cap` entries.
    - `:agent.lease/*`      — a best-effort single-runner guard (see
      `commoncrawl.store`'s LEASE CAVEAT) so two overlapping scheduler
      firings don't both drive the seed list at once; a TTL'd lease is
      also the crash-recovery mechanism — a run that dies mid-tick
      without releasing its lease simply lets the NEXT scheduled
      invocation proceed once the TTL elapses, rather than wedging the
      actor permanently.
    - `:agent.budget/*`     — fetches already spent against a `tick-id`,
      so a retried/resumed tick (same `tick-id` passed back in) doesn't
      exceed the original budget even across a crash+retry.
    - `:agent.tick/*`       — an append-only per-tick summary log,
      separate from the commit/hold decision ledger."
  (:require [commoncrawl.operation :as op]
            [commoncrawl.seeds :as seeds]
            [commoncrawl.store :as store]))

(def default-lease-id "commoncrawl-tick")
(def default-lease-ttl-ms (* 15 60 1000))
(def default-budget-cap
  "Per-tick fetch ceiling — the ADR's explicit safety constraint ('大量
  ドメインへの一斉fetchはしない、1tickの予算制限を必ず実装する'). Small on
  purpose: this actor ingests a slowly, deliberately growing corpus, not a
  bulk crawl."
  3)

(defn- next-indices
  "Starting at `cursor` (mod `n`), the next `k` indices into a seed list of
  length `n`, wrapping around. `n` <= 0 -> []."
  [cursor k n]
  (if (or (zero? n) (<= k 0))
    []
    (vec (take k (map #(mod (+ cursor %) n) (range))))))

(defn tick!
  "Runs one bounded tick. opts:
    :store          (required) a commoncrawl.store/Store
    :actor          (required) a compiled commoncrawl.operation graph
    :seeds          seed list (default: commoncrawl.seeds/load-seeds)
    :exclude        exclude-domain set (default #{})
    :budget-cap     max seeds attempted this tick (default `default-budget-cap`)
    :lease-id       (default `default-lease-id`)
    :lease-ttl-ms   (default `default-lease-ttl-ms`)
    :owner          lease-holder identity string (default a random-ish tag)
    :tick-id        pass the SAME id back in to resume a crashed tick's
                    budget accounting; default: a fresh id from `now-ms`
    :now-ms         injectable clock (default: commoncrawl.identity/now-ms
                    — passed in here so tests are deterministic without an
                    extra require cycle; see test/commoncrawl/loop_test.cljc)

  Returns {:skipped :lease-held} if another owner currently holds the
  lease, else a tick summary map (also appended to `store`'s tick log via
  `commoncrawl.store/append-tick!`):
    {:tick-id :attempted :committed :held :budget-used :budget-cap
     :results [{:seed :disposition} ...]}."
  [{:keys [store actor seeds exclude budget-cap lease-id lease-ttl-ms owner tick-id now-ms]
    :or {exclude #{}
         budget-cap default-budget-cap
         lease-id default-lease-id
         lease-ttl-ms default-lease-ttl-ms
         owner (str "commoncrawl-" #?(:clj (.hashCode (Thread/currentThread)) :cljs (js/Math.floor (* (js/Math.random) 1e9))))
         now-ms (fn [] #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))}}]
  (let [seeds (or seeds (seeds/load-seeds))
        now (now-ms)
        tick-id (or tick-id (str "tick-" now))]
    (if-not (store/acquire-lease! store lease-id owner now lease-ttl-ms)
      {:skipped :lease-held :tick-id tick-id}
      (try
        (let [n (count seeds)
              used0 (store/tick-budget-used store tick-id)
              remaining (max 0 (- budget-cap used0))
              cursor0 (store/cursor store)
              idxs (next-indices cursor0 remaining n)
              results
              (vec
               (for [i idxs
                     :let [seed (-> (nth seeds i) (select-keys [:domain :url]))
                           context {:seeds seeds :exclude exclude
                                    :budget {:used (store/tick-budget-used store tick-id) :cap budget-cap}}
                           run (op/run-seed! actor seed context)]]
                 (do
                   (store/record-fetch! store tick-id)
                   {:seed seed :disposition (get-in run [:state :disposition])})))
              committed (count (filter #(= :commit (:disposition %)) results))
              held (count (filter #(= :hold (:disposition %)) results))
              used-final (store/tick-budget-used store tick-id)
              summary {:t :tick-summary :tick-id tick-id :started-at now
                       :attempted (count results) :committed committed :held held
                       :budget-used used-final :budget-cap budget-cap
                       :results results}]
          (when (seq idxs) (store/advance-cursor! store (mod (inc (last idxs)) (max 1 n))))
          (store/append-tick! store summary)
          summary)
        (finally
          (store/release-lease! store lease-id owner))))))
