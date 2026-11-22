(ns bb-agent.action-receipts
  "Outbound action-claim vs. tool-receipt auditor, 0600 monthly JSONL receipt
   log, and PII-free failure-signature normalizer for RSI error clustering."
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

(def ^:private tool->evidence
  {"write_file" #{:file}
   "edit_file" #{:file}
   "shell" #{:shell :file :send}
   "telegram_send_file" #{:send :file}
   "web_send_file" #{:send :file}
   "browser_read" #{:browser}
   "a2a_task" #{:send :a2a}
   "launch_goal" #{:goal :file}})

(def ^:private claim-patterns
  [[:send #"(?i)\b(?:i(?:'ve| have)?|and)\s+(?:sent|emailed|messaged|posted|notified|uploaded)\b"]
   [:file #"(?i)\b(?:i(?:'ve| have)?|and)\s+(?:wrote|written|saved|edited|downloaded|created|updated|deleted)\b"]])

(def ^:private negation-prefix-re
  #"(?i)\b(?:did not|didn't|have not|haven't|could not|couldn't|cannot|can't|unable to|will not|won't)\b")

(defn- strip-fenced-blocks [s]
  (str/replace (str (or s "")) #"(?s)```.*?```" " "))

(defn tool-evidence
  "Set of verified action kinds backed by successful executed tool results."
  [tool-results]
  (reduce (fn [acc r]
            (if (and (= :ok (:status r)) (not (false? (:executed? r))))
              (into acc (get tool->evidence (:tool/name r) #{}))
              acc))
          #{}
          (or tool-results [])))

(defn claimed-actions
  "Extract declarative past-tense action kinds claimed in prose (ignoring
   fenced code blocks and negated sentences)."
  [reply]
  (let [cleaned (strip-fenced-blocks reply)
        sentences (str/split cleaned #"(?<=[.!?\n])\s+")]
    (->> sentences
         (remove #(re-find negation-prefix-re %))
         (mapcat (fn [sent]
                   (keep (fn [[kind re]] (when (re-find re sent) kind))
                         claim-patterns)))
         distinct
         vec)))

(defn unbacked-claims
  "Return vector of claimed action kinds in `reply` not backed by `tool-results`."
  [reply tool-results]
  (let [ev (tool-evidence tool-results)]
    (vec (remove ev (claimed-actions reply)))))

(defn- receipts-file []
  (let [ym (.format (LocalDate/now) (DateTimeFormatter/ofPattern "yyyy-MM"))]
    (fs/path (config/home) "state" "receipts" (str ym ".jsonl"))))

(defn append-receipt!
  "Append one minimal tool-only receipt line (no payloads) to 0600 monthly JSONL."
  [entry]
  (let [p (receipts-file)
        line (str (json/generate-string
                   (assoc (select-keys entry [:session/id :tool :kind :ok? :ms])
                          :at (System/currentTimeMillis)))
                  "\n")]
    (fs/create-dirs (fs/parent p))
    (aio/ensure-private-file! p)
    (spit (str p) line :append true)
    p))

;; ---------- PII-free failure-signature normalizer ----------

(defn normalize-signature-text
  "Strip quoted strings, URLs, emails, paths, UUIDs, hex IDs, and numbers so
   identical failure classes hash to the same key without leaking PII."
  [text]
  (-> (str (or text ""))
      str/lower-case
      (str/replace #"https?://[^\s\"'<>]+" "<url>")
      (str/replace #"[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}" "<email>")
      (str/replace #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" "<uuid>")
      (str/replace #"\"[^\"]*\"|'[^']*'" "<q>")
      (str/replace #"(?:/[^\s:,;\"']+)+" "<path>")
      (str/replace #"\b0x[0-9a-f]{6,}\b|\b[0-9a-f]{12,}\b" "<hex>")
      (str/replace #"\b\d+\b" "<n>")
      (str/replace #"\s+" " ")
      str/trim))

(defn failure-key
  "Deterministic `kind:<16-hex>` key for PII-free RSI failure clustering."
  [kind text]
  (let [norm (normalize-signature-text text)
        md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str (name kind) ":" norm) StandardCharsets/UTF_8))
        hex (apply str (map #(format "%02x" (bit-and 0xff (long %))) (take 8 bs)))]
    (str (name kind) ":" hex)))
