(ns bb-agent.log-cap
  "Bounded poller log (bk-e6ff). launchd appends stdout+stderr to
   state/telegram-poll.log forever; nothing rotates it. cap-log! runs once
   at poller boot: over the byte budget, the file is truncated IN PLACE
   keeping the newest tail — never renamed, because launchd holds an
   O_APPEND fd on the inode and a rename would send every future line to
   an unlinked ghost file. In-place truncation is safe with O_APPEND:
   the kernel re-seeks to end-of-file on every write."
  (:require [clojure.java.io :as io])
  (:import [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]))

(def default-max-bytes (* 10 1024 1024))
(def default-keep-bytes (* 5 1024 1024))

(defn cap-log!
  "If the log at `path` exceeds max-bytes, keep its newest ~keep-bytes
   (advanced to a newline boundary so the tail starts on a whole line)
   preceded by a marker recording what was dropped. Returns a report map
   when it capped, nil when there was nothing to do (missing or small
   file). Never throws — a log-cap bug must not stop the poller."
  ([path] (cap-log! path default-max-bytes default-keep-bytes))
  ([path max-bytes keep-bytes]
   (try
     (let [f (io/file (str path))]
       (when (and (.isFile f) (> (.length f) (long max-bytes)))
         (let [before (.length f)]
           (with-open [raf (RandomAccessFile. f "rw")]
             (.seek raf (max 0 (- before (long keep-bytes))))
             (.readLine raf) ;; discard the partial line the seek landed in
             (let [tail-start (.getFilePointer raf)
                   tail-len (- before tail-start)
                   tail (byte-array tail-len)
                   marker (.getBytes
                           (str "…[log capped at boot: dropped "
                                (- before tail-len) " older bytes @ "
                                (java.time.Instant/now) "]…\n")
                           StandardCharsets/UTF_8)]
               (.readFully raf tail)
               (.seek raf 0)
               (.write raf marker)
               (.write raf tail)
               (.setLength raf (+ (alength marker) tail-len))))
           {:capped? true :path (str path) :before-bytes before
            :after-bytes (.length f)})))
     (catch Exception e
       (println (str "log cap failed (non-fatal): " (.getMessage e)))
       nil))))
