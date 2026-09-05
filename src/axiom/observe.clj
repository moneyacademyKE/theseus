(ns axiom.observe
  "Builds the world map by running configured shell observers.

  Per mission: 'The filesystem is the only source of truth.' Every
  observer is a shell command; its parsed output becomes one key in the
  world map. The world is re-derived from disk EVERY iteration -- never
  trusted from the agent's self-report."
  (:require [babashka.process :refer [sh]]
            [clojure.string :as str]))

(defn- run-observer
  "Run one shell command via bash -c (supports pipes/globs), return stdout (trimmed)."
  [cmd opts]
  (let [res (sh {:out :string :err :string
                 :dir (:dir opts)
                 :continue true} "bash" "-c" cmd)]
    (-> res :out str/trim)))

(defn- parse
  "Parse raw string per :parse spec. Default :string."
  [raw parse-type]
  (case (or parse-type :string)
    :int        (try (Long/parseLong (first (re-seq #"-?\d+" raw)))
                     (catch Exception _ 0))
    :string     raw
    :bool       (= (str/lower-case raw) "true")
    :lines      (count (remove str/blank? (str/split-lines raw)))
    :first-line (-> raw str/split-lines first (or "") str/trim)
    :last-line  (-> raw str/split-lines last (or "") str/trim)
    raw))

(defn build-world
  "Run all observers and return the world map.

  observers: {:key {:sh \"cmd\" :parse :int} ...}
  opts:      {:dir <workdir> :timeout <ms>}

  Observer failures are captured, NOT fatal: a failed observer yields nil
  for its key. Predicates treat nil as missing, so integrity checks catch
  breakage rather than the observer layer crashing the whole loop."
  [observers opts]
  (reduce-kv
   (fn [world key spec]
     (let [cmd  (:sh spec)
           ptype (:parse spec)]
       (assoc world key
              (try
                (-> (run-observer cmd opts) (parse ptype))
                (catch Exception _ nil)))))
   {}
   observers))
