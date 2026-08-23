;; Exit-0 benchmark harness — `bb benchmark`.
;; Shape ported from AlcaponeCoder's tests/benchmark.clj: named
;; scenarios, wall-clock timing, hard assertions, and the process exit
;; code tells the truth. Everything runs offline: the deterministic
;; :fake provider or a loopback httpkit server. No new deps.
(ns bench.harness
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]))

(def ^:private iters 3)

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- sh! [home & args]
  (apply p/shell {:out :string
                  :err :string
                  :continue true
                  :extra-env {"OPENCRABS_HOME" (str home)}}
         "bb" args))

(defn- write-config! [home config]
  (fs/create-dirs home)
  (spit (str (fs/path home "config.edn")) (pr-str config)))

(defn- flaky-server [port]
  (let [hits (atom 0)]
    {:stop (server/run-server
            (fn [_]
              (if (zero? (mod (swap! hits inc) 3))
                {:status 200
                 :headers {"content-type" "application/json"}
                 :body (json/generate-string
                        {:choices [{:message {:role "assistant"
                                              :content "pong-retry"}}]})}
                {:status 503
                 :headers {"content-type" "application/json"}
                 :body "{}"}))
            {:port port})
     :hits (fn [] @hits)}))

(defn- scenarios []
  [{:name "plain-turn"
    :prompt "say pong"
    :expect "pong"
    :prepare (fn [_home])}
   {:name "memory-recall"
    :prompt "use memory theseus"
    :expect "memory="
    :prepare (fn [home]
               (write-config! home {})
               (sh! home "memory" "add" "theseus bench memory"))}
   {:name "tool-denial"
    :prompt "try denied shell echo hi"
    :expect "tool-results="
    :prepare (fn [_home])}
   {:name "retry-absorb"
    :prompt "say pong"
    :expect "pong-retry"
    :flaky true
    :prepare (fn [_home])}])

(defn- run-scenario [{:keys [name prompt expect flaky prepare]}]
  (let [home (fs/create-temp-dir {:prefix (str "opencrabs-bench-" name "-")})
        port (when flaky (free-port))
        ctx (when flaky (flaky-server port))]
    (try
      (prepare home)
      (when flaky
        (write-config! home {:provider :openai-compatible
                             :model "bench-model"
                             :providers {:openai-compatible
                                         {:base-url (str "http://127.0.0.1:" port "/v1")
                                          :api-key "bench-key"}}}))
      (let [timings (for [_ (range iters)]
                      (let [t0 (System/nanoTime)
                            r (sh! home "agent" prompt)
                            ms (long (/ (- (System/nanoTime) t0) 1e6))
                            ok (and (zero? (:exit r))
                                    (str/starts-with? (str (:out r)) expect))]
                        {:ms ms :ok ok}))]
        {:name name
         :ok (every? :ok timings)
         :timings (mapv :ms timings)
         :hits (when ctx ((:hits ctx)))})
      (finally
        (when ctx ((:stop ctx)))
        (fs/delete-tree home)))))

(defn- stats [ms]
  (let [sorted (sort ms)]
    [(first sorted)
     (long (/ (apply + ms) (count ms)))
     (last sorted)]))

(defn -main []
  (println "theseus benchmark —" iters "iterations per scenario, offline\n")
  (let [results (doall (map run-scenario (scenarios)))]
    (println (format "%-16s %7s %7s %7s %5s %6s"
                     "scenario" "min-ms" "mean-ms" "max-ms" "ok" "hits"))
    (println (str/join "" (repeat 53 "-")))
    (doseq [{:keys [name ok timings hits]} results
            :let [[mn mean mx] (stats timings)]]
      (println (format "%-16s %7d %7d %7d %5s %6s"
                       name mn mean mx (if ok "yes" "FAIL") (or hits "-"))))
    (let [failed (remove :ok results)]
      (if (seq failed)
        (do (println "\nFAILED scenarios:" (str/join ", " (map :name failed)))
            (System/exit 1))
        (do (println "\nall scenarios green")
            (System/exit 0))))))

(-main)
