(ns bb-agent.session
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
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

(def ^:private secret-patterns
  "Secret shapes that must never persist in session records. Kept as data:
   extend by appending a pattern, never by special-casing call sites."
  [#"\d{8,}:AA[A-Za-z0-9_-]{20,}"          ; Telegram bot tokens
   #"sk-[A-Za-z0-9][A-Za-z0-9_-]{7,}"])    ; API keys (sk-*, case-sensitive)

(defn redact-secrets
  "Replace known secret shapes in s with a redaction marker. Pure. Used at
   the persistence seam and by channel status renderers — a tool call's
   args can carry a token, and no surface may ever show one."
  [s]
  (reduce (fn [acc pat] (str/replace acc pat "<REDACTED-SECRET>"))
          s secret-patterns))

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
                             ;; Corrupt file under lock — archive and start clean
                             (try
                               (let [corrupt (str path ".corrupt-" (System/currentTimeMillis))]
                                 (spit corrupt raw))
                               (catch Exception _ nil))
                             [])))
              updated (vec (f (vec turns)))
              payload (redact-secrets (pr-str updated))]
          (channel-overwrite-all ch payload)
          (fs/set-posix-file-permissions path "rw-------")
          updated)))))

(defn append-turn! [session-id turn]
  (atomic-mutate-session! session-id #(conj % turn)))

(defn save-turns! [session-id turns]
  (atomic-mutate-session! session-id (constantly (vec turns))))

(defn load-metadata [session-id]
  (let [path (metadata-file session-id)]
    (when (fs/regular-file? path)
      (edn/read-string (slurp (str path))))))

(defn save-metadata! [session-id metadata]
  (let [path (metadata-file session-id)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str metadata))
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
           (filter fs/regular-file?)
           (map #(edn/read-string (slurp (str %))))
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

(defn reset!
  "Archive the session's turn history: the file moves aside with a
   timestamp suffix, never deleted. The next turn starts fresh."
  [session-id]
  (let [f (session-file session-id)]
    (when (fs/exists? f)
      (fs/move f (fs/path (str f ".archived-" (System/currentTimeMillis))))
      true)))
