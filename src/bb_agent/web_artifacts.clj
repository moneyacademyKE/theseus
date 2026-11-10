(ns bb-agent.web-artifacts
  "Authenticated delivery of generated files to the web cockpit.

  The Telegram lane delivers files through the Telegram API
  (tool/telegram.clj); the web lane delivers them here. One registry
  maps artifact ids to validated local paths, and the HTTP route
  serves bytes by id — never by caller-supplied path. Validation runs
  at registration AND at serve time: a path that turned into a symlink
  after registration is refused.

  The registry is process-local and deliberately not persisted: an
  artifact id is a handle to a live conversation's output, not a
  durable file share. The files themselves stay wherever the turn put
  them."
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [clojure.string :as str]))

(def ^:private max-artifact-bytes (* 50 1024 1024))

(def registry
  "artifact-id -> {:path :name :bytes :caption :job-id}. Public for the
  route and tests."
  (atom {}))

(defn- allowed-roots
  "Delivery roots: the process cwd (the repo) and the Theseus home. A
  file outside both is not the deck's to serve."
  []
  [(fs/canonicalize (System/getProperty "user.dir"))
   (fs/canonicalize (config/home))])

(defn- under-root?
  "True when canonical p sits under one of the allowed roots. fs/path
  starts-with is component-wise, so '/Users/moe/theseus-evil' does not
  sneak under '/Users/moe/theseus'."
  [p]
  (let [c (try (fs/canonicalize p) (catch Exception _ nil))]
    (boolean (and c (some #(fs/starts-with? c %) (allowed-roots))))))

(defn- artifact-id []
  (subs (str (java.util.UUID/randomUUID)) 0 12))

(defn register!
  "Validate and register one file for delivery. Returns the artifact
  map (with :artifact/id) or throws ex-info {:web/artifact? true
  :status 4xx} with the reason. job-id rides along for the job view."
  [path job-id caption]
  (let [p (fs/path (str path))]
    (cond
      (not (fs/regular-file? p))
      (throw (ex-info "no such file" {:web/artifact? true :status 404}))
      (not (under-root? p))
      (throw (ex-info "path outside the delivery roots"
                      {:web/artifact? true :status 403}))
      (> (fs/size p) max-artifact-bytes)
      (throw (ex-info (str "artifact exceeds " max-artifact-bytes " bytes")
                      {:web/artifact? true :status 413}))
      :else
      (let [id (artifact-id)
            art {:artifact/id id
                 :path (str p)
                 :name (fs/file-name p)
                 :bytes (fs/size p)
                 :caption (when (seq (str caption)) (subs (str caption) 0
                                                          (min 200 (count (str caption)))))
                 :job-id (str job-id)
                 :registered-at (System/currentTimeMillis)}]
        (swap! registry assoc id art)
        art))))

(defn artifacts-for
  "The registered artifacts of one job, newest last — the job view's
  chips."
  [job-id]
  (->> (vals @registry)
       (filter #(= (str job-id) (:job-id %)))
       (sort-by :registered-at)
       (mapv #(select-keys % [:artifact/id :name :bytes :caption]))))

(defn lookup
  "Serve-time validation: the id must exist, the file must still be a
  regular file, and the RESOLVED path must still sit under the roots
  (symlink swap check). Returns the artifact map or nil."
  [id]
  (when-let [art (get @registry (str id))]
    (let [p (fs/path (:path art))]
      (when (and (fs/regular-file? p) (under-root? p))
        art))))

(def content-types
  "Extension -> Content-Type for serving. Unknown extensions serve as
  octet-stream (download, never interpreted as a page)."
  {"md"   "text/markdown; charset=utf-8"
   "txt"  "text/plain; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "csv"  "text/csv; charset=utf-8"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "webp" "image/webp"
   "svg"  "image/svg+xml"
   "pdf"  "application/pdf"
   "html" "text/plain; charset=utf-8"
   "edn"  "text/plain; charset=utf-8"
   "clj"  "text/plain; charset=utf-8"})

(defn content-type
  "Content-Type by extension; html serves as text/plain — the deck never
  interprets a served artifact as a page."
  [name]
  (or (get content-types (some-> (fs/extension (str name)) str str/lower-case))
      "application/octet-stream"))
