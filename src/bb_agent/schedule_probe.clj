(ns bb-agent.schedule-probe
  "Two-stage sandboxed schedule probes and pacing gate.
   Runs zero-AI probe scripts inside macOS sandbox-exec (Seatbelt SBPL) with
   stripped env (zero API keys) and denied private dirs (.ssh/.aws/.gnupg),
   waking the LLM ONLY when output changed or the last line is JSON
   {\"wakeAgent\": true}. Includes boot hold, daily AI-wake cap, and
   deterministic-jitter exponential backoff."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def ^:private base-env-names #{"PATH" "HOME" "LANG" "LC_ALL" "TMPDIR" "USER" "SHELL"})
(def boot-hold-ms (* 2 60 1000))
(def ^:private min-backoff-ms (* 15 60 1000))
(def ^:private max-backoff-ms (* 4 60 60 1000))

(defn sha256-hex [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str (or s "")) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff (long %))) bs))))

(defn sanitized-env
  "Strip all API keys and secrets; keep only safe base shell variables."
  [env-map]
  (into {} (filter (fn [[k _]] (contains? base-env-names (str k))) env-map)))

(defn seatbelt-profile
  "Generate a macOS Seatbelt SBPL profile: read-only root, scratch-only writes,
   explicitly denying private credential directories."
  [scratch-dir]
  (let [home (or (System/getenv "HOME") "/Users")
        scratch (str (or scratch-dir "/tmp"))]
    (str "(version 1)\n"
         "(allow default)\n"
         "(deny file-write*)\n"
         "(allow file-write* (subpath \"" scratch "\") (subpath \"/tmp\") (subpath \"/private/tmp\"))\n"
         "(deny file-read* "
         "(subpath \"" home "/.ssh\") "
         "(subpath \"" home "/.aws\") "
         "(subpath \"" home "/.gnupg\") "
         "(literal \"" home "/.netrc\") "
         "(subpath \"" home "/Library/Keychains\"))\n")))

(defn wake-asked?
  "True when the last non-blank line of probe stdout is JSON with wakeAgent=true."
  [stdout]
  (let [last-line (->> (str/split-lines (str (or stdout "")))
                       (map str/trim)
                       (remove str/blank?)
                       last)]
    (if (and last-line (str/starts-with? last-line "{") (str/ends-with? last-line "}"))
      (try
        (let [parsed (json/parse-string last-line true)]
          (boolean (or (:wakeAgent parsed) (:wake-agent parsed))))
        (catch Exception _ false))
      false)))

(defn evaluate-probe-output
  "Compare probe output hash against previous hash or explicit wakeAgent JSON."
  [stdout prev-hash]
  (let [h (sha256-hex stdout)
        asked? (wake-asked? stdout)
        changed? (not= h prev-hash)]
    {:hash h
     :changed? changed?
     :wake-asked? asked?
     :wake? (or asked? changed?)}))

(defn boot-hold?
  "True during the initial 2-minute post-boot hold window."
  [boot-ms now-ms]
  (< (- (long now-ms) (long boot-ms)) boot-hold-ms))

(defn daily-cap-reached?
  [runs-today max-per-day]
  (and (integer? max-per-day)
       (pos? max-per-day)
       (>= (long (or runs-today 0)) (long max-per-day))))

(defn deterministic-backoff-ms
  "Exponential backoff (15m -> 4h) with deterministic jitter seeded by run-id."
  [run-id consecutive-failures]
  (let [n (max 1 (long (or consecutive-failures 1)))
        base (min max-backoff-ms (long (* min-backoff-ms (Math/pow 2.0 (dec n)))))
        seed (Long/parseUnsignedLong (subs (sha256-hex (str run-id ":" n)) 0 8) 16)
        jitter-pct (/ (double (mod seed 20)) 100.0)]
    (min max-backoff-ms (long (* base (+ 1.0 jitter-pct))))))

(defn run-probe!
  "Execute a zero-AI probe script inside macOS sandbox-exec (when available)
   with sanitized env. Returns {:ok? bool :exit int :stdout str :stderr str :hash str :wake? bool}."
  [script {:keys [prev-hash timeout-ms scratch-dir] :or {timeout-ms 15000}}]
  (let [scratch (or scratch-dir (str (fs/create-temp-dir {:prefix "theseus-probe-"})))
        use-seatbelt? (and (= "Mac OS X" (System/getProperty "os.name"))
                           (fs/exists? "/usr/bin/sandbox-exec"))
        argv (if use-seatbelt?
               ["/usr/bin/sandbox-exec" "-p" (seatbelt-profile scratch) "bash" "-c" script]
               ["bash" "-c" script])
        proc (p/process argv {:out :string
                              :err :string
                              :extra-env nil
                              :env (sanitized-env (System/getenv))})
        res (deref proc timeout-ms ::timeout)]
    (if (= ::timeout res)
      (do (p/destroy-tree proc)
          {:ok? false :exit -1 :stdout "" :stderr "probe timed out" :wake? false})
      (let [evald (evaluate-probe-output (:out res) prev-hash)]
        (merge evald
               {:ok? (zero? (:exit res))
                :exit (:exit res)
                :stdout (:out res)
                :stderr (:err res)})))))
