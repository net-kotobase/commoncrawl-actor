(ns commoncrawl.loop-test
  "The durable outer loop — budget capping, cursor round-robin across
  ticks, and the lease's crash-recovery property (a stale lease can be
  re-acquired after its TTL elapses)."
  (:require [clojure.test :refer [deftest is]]
            [commoncrawl.loop :as loop]
            [commoncrawl.operation :as op]
            [commoncrawl.store :as store]))

(def five-seeds
  (mapv (fn [i] {:domain (str "seed" i ".example.org") :url (str "https://seed" i ".example.org/")})
        (range 5)))

(defn- mock-actor []
  (op/build (store/mem-store)
            {:advise-fn (constantly {:category "c" :summary "s" :entities [] :confidence 0.9})
             :fetch-fn (constantly [{:timestamp "1" :status "200" :filename "f.warc.gz" :offset "0" :length "1"}])
             :warc-fetch-fn (constantly (str "WARC/1.0\r\nWARC-Type: response\r\n\r\nHTTP/1.1 200 OK\r\n\r\n<html>hi</html>"))
             :ingest-fn (constantly {:ok true})}))

(deftest tick-respects-budget-cap
  (let [st (store/mem-store)
        actor (mock-actor)
        summary (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 2
                             :owner "test-owner" :now-ms (constantly 1000)})]
    (is (= 2 (:attempted summary)))
    (is (= 2 (:committed summary)))
    (is (= 2 (:budget-used summary)))))

(deftest consecutive-ticks-advance-the-round-robin-cursor
  (let [st (store/mem-store)
        actor (mock-actor)
        _ (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 2 :owner "o1" :now-ms (constantly 1000)})
        tick2 (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 2 :owner "o2" :now-ms (constantly 2000)})]
    (is (= [{:domain "seed2.example.org" :url "https://seed2.example.org/"}
            {:domain "seed3.example.org" :url "https://seed3.example.org/"}]
           (mapv :seed (:results tick2)))
        "the second tick must cover DIFFERENT seeds than the first, not restart at index 0")))

(deftest cursor-wraps-around-the-seed-list
  (let [st (store/mem-store)
        actor (mock-actor)]
    (store/advance-cursor! st 4)
    (let [summary (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 3 :owner "o" :now-ms (constantly 1000)})]
      (is (= ["seed4.example.org" "seed0.example.org" "seed1.example.org"]
             (mapv (comp :domain :seed) (:results summary)))))))

(deftest a-held-lease-skips-the-tick
  (let [st (store/mem-store)
        actor (mock-actor)]
    (store/acquire-lease! st loop/default-lease-id "other-owner" 1000 60000)
    (let [summary (loop/tick! {:store st :actor actor :seeds five-seeds :owner "me" :now-ms (constantly 1500)})]
      (is (= :lease-held (:skipped summary)))
      (is (empty? (store/tick-log st))))))

(deftest a-stale-lease-is-reclaimed-crash-recovery
  (let [st (store/mem-store)
        actor (mock-actor)]
    (store/acquire-lease! st loop/default-lease-id "crashed-owner" 1000 500) ;; expires at 1500
    (let [summary (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 1
                               :owner "recovering-owner" :now-ms (constantly 2000)})]
      (is (not (:skipped summary)) "TTL elapsed -- a new owner may proceed even though the
                                    crashed owner never released the lease"))))

(deftest tick-releases-its-own-lease-when-done
  (let [st (store/mem-store)
        actor (mock-actor)]
    (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 1 :owner "o1" :now-ms (constantly 1000)})
    (is (true? (store/acquire-lease! st loop/default-lease-id "o2" 1001 60000))
        "a completed tick must release its lease, not hold it until TTL")))

(deftest tick-appends-exactly-one-tick-summary
  (let [st (store/mem-store)
        actor (mock-actor)]
    (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 2 :owner "o" :now-ms (constantly 1000)})
    (is (= 1 (count (store/tick-log st))))))

(deftest mixed-commit-and-hold-counts
  (let [st (store/mem-store)
        actor (op/build (store/mem-store)
                        {:advise-fn (constantly {:category "" :summary "" :entities [] :confidence 0.9})
                         :fetch-fn (constantly nil) ;; every fetch misses -> every seed holds
                         :ingest-fn (constantly {:ok true})})
        summary (loop/tick! {:store st :actor actor :seeds five-seeds :budget-cap 3 :owner "o" :now-ms (constantly 1000)})]
    (is (= 0 (:committed summary)))
    (is (= 3 (:held summary)))))
