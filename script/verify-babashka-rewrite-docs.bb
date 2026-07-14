#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def required-files
  ["docs/babashka-rewrite/README.md"
   "docs/babashka-rewrite/PRODUCT_SPEC.md"
   "docs/babashka-rewrite/ROADMAP.md"
   "docs/babashka-rewrite/TASKLIST.md"
   "docs/babashka-rewrite/adr/0001-babashka-only-rewrite.md"])

(def required-phrases
  {"docs/babashka-rewrite/PRODUCT_SPEC.md"
   ["CLI agent"
    "Tool execution"
    "Provider HTTP calls"
    "Simple memory"
    "Daemon and scheduler"
    "Telegram bot"
    "Out Of Scope"
    "WhatsApp native client"]

   "docs/babashka-rewrite/ROADMAP.md"
   ["Phase 1: Minimal CLI Agent"
    "Phase 2: Tool Protocol And Safe Execution"
    "Phase 3: Memory And Context"
    "Phase 4: Scheduler And Daemon"
    "Phase 5: One Chat Channel"
    "bb test:e2e:all"]

   "docs/babashka-rewrite/TASKLIST.md"
   ["P1-001"
    "P2-001"
    "P3-001"
    "P4-001"
    "P5-001"
    "Loop Protocol"]

   "docs/babashka-rewrite/adr/0001-babashka-only-rewrite.md"
   ["Direct transpilation"
    "Reject"
    "Babashka semantic rewrite"
    "Accept"]})

(defn fail! [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(doseq [file required-files]
  (when-not (fs/regular-file? file)
    (fail! (str "Missing required file: " file))))

(doseq [[file phrases] required-phrases]
  (let [content (slurp file)]
    (doseq [phrase phrases]
      (when-not (str/includes? content phrase)
        (fail! (str "Missing phrase in " file ": " phrase))))))

(println "Babashka rewrite docs verified.")
