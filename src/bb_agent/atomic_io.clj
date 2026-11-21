(ns bb-agent.atomic-io
  "Shared born-0600 atomic file writes and companion-file locking.

   Centralizes the Java NIO POSIX file-creation, atomic-rename, and
   JVM + FileChannel companion-lock primitives so config, sessions,
   approvals, web auth, outbox, notes, and subagents share one tested seam."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file FileAlreadyExistsException Files OpenOption Paths
                          StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.concurrent.locks ReentrantLock)))

(def ^:private empty-attrs (into-array FileAttribute []))
(def ^:private empty-open-opts (into-array OpenOption []))
(def ^:private atomic-move-opts
  (into-array java.nio.file.CopyOption
              [StandardCopyOption/REPLACE_EXISTING
               StandardCopyOption/ATOMIC_MOVE]))
(def ^:private replace-move-opts
  (into-array java.nio.file.CopyOption
              [StandardCopyOption/REPLACE_EXISTING]))
(def ^:private lock-open-opts
  (into-array OpenOption
              [StandardOpenOption/CREATE
               StandardOpenOption/READ
               StandardOpenOption/WRITE]))

(def ^:private ^ConcurrentHashMap jvm-locks (ConcurrentHashMap.))

(defn- jvm-lock-for ^ReentrantLock [lock-path]
  (let [k (str lock-path)]
    (or (.get jvm-locks k)
        (let [fresh (ReentrantLock.)]
          (or (.putIfAbsent jvm-locks k fresh) fresh)))))

(defn- create-private-file! [^java.nio.file.Path p]
  (try
    (let [attr (PosixFilePermissions/asFileAttribute
                (PosixFilePermissions/fromString "rw-------"))]
      (Files/createFile p (into-array FileAttribute [attr])))
    (catch UnsupportedOperationException _
      (Files/createFile p empty-attrs))))

(defn ensure-private-file!
  "Create path with owner-only (0600) permissions if it does not yet exist."
  [path]
  (let [p (.toPath (java.io.File. (str path)))]
    (when-not (Files/exists p (into-array java.nio.file.LinkOption []))
      (try
        (create-private-file! p)
        (catch FileAlreadyExistsException _ nil)))))

(defn write-private-file!
  "Atomically write UTF-8 content to path with owner-only (0600) permissions
   from birth. Cleans up the temp file on any error."
  [path content]
  (let [target (.toPath (java.io.File. (str path)))
        parent (.getParent target)]
    (when parent
      (Files/createDirectories parent empty-attrs))
    (let [tmp (Paths/get (str parent)
                         (into-array String [(str "." (.getFileName target)
                                                  ".tmp." (System/nanoTime)
                                                  "-" (rand-int 100000))]))
          bytes (.getBytes (str content) StandardCharsets/UTF_8)]
      (try
        (create-private-file! tmp)
        (Files/write tmp bytes empty-open-opts)
        (try
          (Files/move tmp target atomic-move-opts)
          (catch Exception _
            (Files/move tmp target replace-move-opts)))
        (finally
          (try (Files/deleteIfExists tmp)
               (catch Exception _ nil)))))))

(defn with-file-lock
  "Serialize f across both JVM threads and OS processes using a companion
   lock file (never locking the target data file directly, which breaks under
   atomic rename-replace and POSIX fcntl close-drop semantics)."
  [lock-path f]
  (let [lock-str (str lock-path)
        lock-p (.toPath (java.io.File. lock-str))
        ^ReentrantLock jlock (jvm-lock-for lock-str)]
    (.lock jlock)
    (try
      (when-let [parent (.getParent lock-p)]
        (Files/createDirectories parent empty-attrs))
      (with-open [ch (FileChannel/open lock-p lock-open-opts)
                  _lock (.lock ch)]
        (f))
      (finally
        (.unlock jlock)))))

(defn read-edn-safe
  "Read EDN from path, returning default-val if missing, blank, or corrupt."
  [path default-val]
  (if (fs/regular-file? path)
    (try
      (let [raw (slurp (str path))]
        (if (str/blank? raw)
          default-val
          (let [parsed (edn/read-string raw)]
            (if (nil? parsed) default-val parsed))))
      (catch Exception _ default-val))
    default-val))

(defn atomic-mutate-edn!
  "Apply (f current-state) -> {:state' new-state :result r} under both JVM and
   companion-file locks. Writes new-state atomically (born 0600) only when
   :state' is non-nil. Returns :result."
  ([path default-val f]
   (atomic-mutate-edn! path (str path ".lock") default-val f))
  ([path lock-path default-val f]
   (with-file-lock lock-path
     (fn []
       (let [state (read-edn-safe path default-val)
             {:keys [state' result]} (f state)]
         (when (some? state')
           (write-private-file! path (pr-str state')))
         result)))))

(defn update-edn!
  "Locked atomic read-modify-write for an EDN file. Returns the updated state."
  ([path default-val f]
   (update-edn! path (str path ".lock") default-val f))
  ([path lock-path default-val f]
   (atomic-mutate-edn! path lock-path default-val
     (fn [state]
       (let [next-state (f state)]
         {:state' next-state :result next-state})))))
