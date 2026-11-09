(ns bb-agent.telegram-upload
  "Outbound local files: documents and photos.

   Validation happens entirely before any network call - existence, size
   cap per kind, caption length - then the multipart request rides the one
   bounded delivery ladder (429 retry, structured terminal failures)."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bb-agent.preflight :as preflight]
            [bb-agent.telegram-delivery :as delivery]))

(def document-max-bytes 52428800)
(def photo-max-bytes 10485760)
(def max-caption-chars 1024)

(def ^:private kind-caps {:document document-max-bytes :photo photo-max-bytes})
(def ^:private kind-endpoints {:document "sendDocument" :photo "sendPhoto"})
(def ^:private kind-fields {:document "document" :photo "photo"})


(def preflight-extensions
  "Alias for bb-agent.preflight/text-extensions — kept so this file's callers do
   not move. The list itself moved to the namespace that owns the guard on
   2026-10-19: a guard that lives privately inside one transport can only ever be
   called by that transport, which is exactly how the curl send paths in
   scripts/ went uninspected for seven days after the guard landed (see
   bb-agent.preflight/outbound-sites, and IRIS's deterministic-path parity)."
  preflight/text-extensions)

(def preflight-max-bytes
  "Alias for bb-agent.preflight/max-inspect-bytes."
  preflight/max-inspect-bytes)

(defn- inspect-outbound!
  "One door. Delegates to bb-agent.preflight/inspect-outbound! and tags the
   refusal as this transport's, so a caller still sees {:telegram/send-file? true}."
  [file]
  (preflight/inspect-outbound! file {:telegram/send-file? true}))

(defn- validate-local-file
  [path kind max-bytes]
  (let [file (io/file (str path))]
    (if (or (str/blank? (str path))
            (not (fs/exists? file))
            (not (fs/regular-file? file)))
      (throw (ex-info (str "Telegram send-file: file not found: " (pr-str (str path)))
                      {:telegram/send-file? true :kind kind}))
      (let [size (fs/size file)]
        (when (> size max-bytes)
          (throw (ex-info (str "Telegram send-file exceeds the " (name kind)
                               " byte limit")
                          {:telegram/send-file? true
                           :file-size size
                           :max-bytes max-bytes})))
        file))))

(defn send-file!
  "Send one local file as a document or photo through the bounded ladder.
   Existence, size, and caption length are validated before any network
   call. Returns {:attempts n :message-id id} or throws structured
   delivery failure data."
  ([cfg chat-id path kind] (send-file! cfg chat-id path kind {}))
  ([cfg chat-id path kind
    {:keys [caption thread-id max-bytes transport sleep-fn]
     :or {max-bytes (get kind-caps kind)}
     :as opts}]
   (when-not (contains? kind-caps kind)
     (throw (ex-info (str "Telegram send-file: unsupported kind " (pr-str kind))
                     {:telegram/send-file? true :kind kind})))
   (when (and caption (> (count caption) max-caption-chars))
     (throw (ex-info (str "Telegram send-file caption exceeds "
                          max-caption-chars " characters")
                     {:telegram/send-file? true
                      :caption-chars (count caption)})))
   (let [file (validate-local-file path kind max-bytes)
         _ (inspect-outbound! file)
         runtime (merge delivery/default-runtime (select-keys opts [:transport :sleep-fn]))
         parts (cond-> [{:name "chat_id" :content (str chat-id)}]
                 caption (conj {:name "caption" :content caption})
                 thread-id (conj {:name "message_thread_id"
                                  :content (str thread-id)})
                 :always (conj {:name (get kind-fields kind) :content file}))]
     (delivery/post-api cfg (get kind-endpoints kind)
                        {:multipart parts}
                        {:chat-id chat-id :thread-id thread-id}
                        runtime))))
