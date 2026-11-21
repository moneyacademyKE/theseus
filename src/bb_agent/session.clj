(ns bb-agent.session
  (:refer-clojure :exclude [reset!])
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [bb-agent.config :as config]
            [bb-agent.redact :as redact]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn safe-session-id [session-id]
  (-> (or session-id "default")
      (str/replace #"[^A-Za-z0-9._-]" "_")))

(defn session-file [session-id]
  (fs/path (config/home) "state" "sessions"
           (str (safe-session-id session-id) ".edn")))

(defn metadata-file [session-id]
  (fs/path (config/home) "state" "session-metadata"
           (str (safe-session-id session-id) ".edn")))

(defn metadata-dir []
  (fs/path (config/home) "state" "session-metadata"))

(def ^:private ensure-private-file! aio/ensure-private-file!)
(def ^:private write-private-file! aio/write-private-file!)

(defn load-turns [session-id]
  (let [path (session-file session-id)]
    (if (fs/regular-file? path)
      (try (or (edn/read-string (slurp (str path))) [])
           (catch Exception _
             ;; A truncated write used to poison the whole session: every
             ;; later append threw on read, so memory froze at the last
             ;; good write (amnesia F1, 2026-09-08). Archive the corpse —
             ;; never eat history silently — and start clean.
             (try
               (fs/move path (fs/path (str path ".corrupt-" (System/currentTimeMillis))))
               (catch Exception _ nil))
             []))
      [])))

(def redact-secrets
  "Replace known secret shapes and runtime-registered literals in s with a
   redaction marker. Delegates to bb-agent.redact."
  redact/redact-secrets)

(defn- channel-read-all [^java.nio.channels.FileChannel ch]
  (.position ch 0)
  (let [sz (.size ch)]
    (if (zero? sz)
      ""
      (let [bb (java.nio.ByteBuffer/allocate (int sz))]
        (while (.hasRemaining bb) (.read ch bb))
        (.flip bb)
        (let [bs (byte-array sz)]
          (.get bb bs)
          (String. bs java.nio.charset.StandardCharsets/UTF_8))))))

(defn- channel-overwrite-all [^java.nio.channels.FileChannel ch ^String s]
  (let [bs (.getBytes s java.nio.charset.StandardCharsets/UTF_8)
        bb (java.nio.ByteBuffer/wrap bs)]
    (.position ch 0)
    (while (.hasRemaining bb) (.write ch bb))
    (.truncate ch (alength bs))
    (.force ch true)))

(defn- atomic-mutate-session!
  "Applies (f current-turns) under an exclusive FileChannel lock on the session
   file, using the SAME open channel for read, truncate, and rewrite.

   CRITICAL (2026-09-08): POSIX fcntl locks are dropped by the kernel the
   instant ANY file descriptor to the same file is closed by the process.
   Slurping or spitting via clojure.java.io within a locked FileChannel opens
   a second fd whose close silently DISCARDS the process's own lock, exposing
   the file to concurrent writers mid-turn. All I/O must ride this channel."
  [session-id f]
  (let [path (session-file session-id)
        parent (fs/parent path)]
    (fs/create-dirs parent)
    (ensure-private-file! path)
    (let [opts (into-array java.nio.file.StandardOpenOption
                           [java.nio.file.StandardOpenOption/CREATE
                            java.nio.file.StandardOpenOption/READ
                            java.nio.file.StandardOpenOption/WRITE])]
      (with-open [ch (java.nio.channels.FileChannel/open path opts)]
        (.lock ch)
        (let [raw (channel-read-all ch)
              turns (if (str/blank? raw)
                      []
                      (try (or (edn/read-string raw) [])
                           (catch Exception _
                             ;; Corrupt file under lock — archive with 0600 and start clean
                             (try
                               (let [corrupt (str path ".corrupt-" (System/currentTimeMillis))]
                                 (write-private-file! corrupt (redact-secrets raw)))
                               (catch Exception _ nil))
                             [])))
              updated (vec (f (vec turns)))
              payload (redact-secrets (pr-str updated))]
          (channel-overwrite-all ch payload)
          (try (fs/set-posix-file-permissions path "rw-------")
               (catch Exception _ nil))
          updated)))))

(def ^:private tool-result-field-cap 8192)
;; Tool stdout can run to hundreds of KB; a session file is re-parsed on
;; every poll cycle, so unbounded results are a tax every future turn pays.
;; Cap string fields inside :tool/results at persistence time — the marker
;; keeps the truncation visible to the model instead of silently lying.
;; In-flight results are untouched: the model still sees the full output
;; for the turn that produced it.

(defn- cap-tool-results
  "Pure: turn -> turn with over-long string fields in :tool/results entries
   truncated to tool-result-field-cap with a visible marker."
  [turn]
  (update turn :tool/results
          (fn [results]
            (mapv (fn [r]
                    (into {}
                          (map (fn [[k v]]
                                 (if (and (string? v) (> (count v) tool-result-field-cap))
                                   [k (str (subs v 0 tool-result-field-cap)
                                           "\n… [truncated " (- (count v) tool-result-field-cap)
                                           " chars at persistence]")]
                                   [k v])))
                          r))
                  (or results [])))))

(defn append-turn! [session-id turn]
  (atomic-mutate-session! session-id #(conj % (cap-tool-results turn))))

(defn drop-last-turn!
  "Remove the newest turn from a session's history — the edit-retry
  seam. The re-run appends its own turn afterwards, so an edit shows
  exactly one replacement, never a duplicate. Atomic on the same lock
  append-turn! rides; a session that is somehow already empty is a
  no-op, not an error."
  [session-id]
  (atomic-mutate-session! session-id #(if (seq %) (pop %) %)))

(defn save-turns! [session-id turns]
  (atomic-mutate-session! session-id (constantly (vec turns))))

(defn- read-metadata-file [path]
  (when (fs/regular-file? path)
    (try
      (let [v (edn/read-string (slurp (str path)))]
        (when (map? v) v))
      (catch Exception _ nil))))

(defn load-metadata [session-id]
  (read-metadata-file (metadata-file session-id)))

(defn save-metadata! [session-id metadata]
  (let [path (metadata-file session-id)]
    (write-private-file! path (pr-str metadata))
    metadata))

(defn touch-metadata! [session-id cfg]
  (let [now (str (java.time.Instant/now))
        existing (load-metadata session-id)
        metadata (merge {:session/id session-id
                         :created/at now}
                        existing
                        {:cwd (or (:cwd existing) (:cwd cfg) (config/home))
                         :provider (:provider cfg)
                         :model (:model cfg)
                         :updated/at now})]
    (save-metadata! session-id metadata)))

(defn list-metadata []
  (let [dir (metadata-dir)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (str (fs/file-name %)) ".edn")))
           (keep read-metadata-file)
           (sort-by (comp str :updated/at))
           reverse
           vec)
      [])))

(defn set-cwd! [session-id cwd]
  (let [path (fs/canonicalize cwd)]
    (when-not (fs/directory? path)
      (throw (ex-info (str "Not a directory: " cwd) {:cwd cwd})))
    (save-metadata! session-id
                    (merge {:session/id session-id
                            :created/at (str (java.time.Instant/now))}
                           (load-metadata session-id)
                           {:cwd (str path)
                            :updated/at (str (java.time.Instant/now))}))))

(defn load-turn-range
  "Load an inclusive slice [from-idx..to-idx] of persisted turns for session-id,
   each annotated with its 0-based :turn/index for provenance drill-down."
  [session-id from-idx to-idx]
  (let [turns (vec (load-turns session-id))
        n (count turns)
        start (max 0 (long (or from-idx 0)))
        end (min (dec n) (long (or to-idx (dec n))))]
    (if (or (zero? n) (> start end))
      []
      (mapv (fn [idx] (assoc (nth turns idx) :turn/index idx))
            (range start (inc end))))))

(defn reset!
  "Archive the session's turn history: the file moves aside with a
   timestamp suffix, never deleted. Clears any compaction checkpoint so the
   next turn starts genuinely fresh."
  [session-id]
  (let [f (session-file session-id)
        ckpt (fs/path (config/home) "state" "session-compaction"
                      (str (safe-session-id session-id) ".edn"))]
    (fs/delete-if-exists ckpt)
    (when (fs/exists? f)
      (fs/move f (fs/path (str f ".archived-" (System/currentTimeMillis))))
      true)))
