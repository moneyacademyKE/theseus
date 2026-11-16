(ns bb-agent.hot-reload
  "Opt-in development supervisor for the long-lived Theseus services.

  It watches source/config/skills/web files and asks launchd to restart only
  the affected service. Production launchd plists are unchanged; the caller
  must explicitly set THESEUS_HOT_RELOAD=1."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def service-labels
  {:web "com.theseus.web"
   :telegram "com.theseus.telegram"})

(defn- ignored-file? [^java.io.File file]
  (let [fname (.getName file)]
    (or (str/starts-with? fname ".")
        (str/includes? fname ".bak-"))))

(defn- regular-files [root]
  (if (fs/directory? root)
    (->> (file-seq (fs/file root))
         (filter #(and (.isFile ^java.io.File %)
                       (not (ignored-file? %)))))
    (if (and (fs/regular-file? root)
             (not (ignored-file? (fs/file root))))
      [(fs/file root)]
      [])))

(defn snapshot
  "Return a path→mtime snapshot for roots. Missing roots are represented by
  no entries, so a later creation is detected naturally."
  [roots]
  (into {}
        (for [root roots
              file (regular-files root)]
          [(str (.toPath ^java.io.File file))
           (.lastModified ^java.io.File file)])))

(defn changed-paths [before after]
  (->> (set (concat (keys before) (keys after)))
       (filter #(not= (get before %) (get after %)))
       sort
       vec))

(defn- source-impact [path]
  (cond
    (or (str/includes? path "/web.clj")
        (str/includes? path "/web_")) #{:web}
    (or (str/includes? path "/telegram")
        (str/includes? path "/telegram_")) #{:telegram}
    :else #{:web :telegram}))

(defn impact
  "Classify changed paths into service restarts and manual plist work."
  [paths]
  (let [paths (map #(str/replace % "\\" "/") paths)
        source? #(or (str/includes? % "/src/")
                     (str/includes? % "/scripts/"))
        web? #(str/includes? % "/web/")
        config? #(or (str/ends-with? % "/config.edn")
                     (str/includes? % "/skills/"))
        plist? #(str/includes? % "/ops/launchd/")]
    {:paths (vec paths)
     :restart (reduce (fn [services path]
                        (cond
                          (web? path) (conj services :web)
                          (config? path) (into services #{:web :telegram})
                          (source? path) (into services (source-impact path))
                          :else services))
                      #{}
                      paths)
     :manual-plists (vec (filter plist? paths))}))

(def ^:private cached-uid
  (delay (str/trim (:out (process/shell {:out :string} "id" "-u")))))

(defn kick! [label]
  (let [result (process/shell {:out :string :err :string :continue true}
                              "launchctl" "kickstart" "-k"
                              (str "gui/" @cached-uid "/" label))]
    (when-not (zero? (:exit result))
      (throw (ex-info "launchctl kickstart failed" {:label label
                                                    :result result})))
    label))

(defn apply-impact! [effect]
  (doseq [service (:restart effect)]
    (kick! (get service-labels service)))
  effect)

(defn- resolve-repo-root [home]
  (let [env-repo (System/getenv "THESEUS_REPO")
        nested (fs/path home "theseus")]
    (cond
      (and (seq env-repo) (fs/directory? env-repo)) (fs/path env-repo)
      (fs/directory? (fs/path nested "src")) nested
      (fs/directory? (fs/path (fs/cwd) "src")) (fs/cwd)
      :else nested)))

(defn roots
  "Resolve watched roots from home + repo root (THESEUS_REPO -> <home>/theseus -> cwd)."
  ([home] (roots home (resolve-repo-root home)))
  ([home repo-root]
   [(fs/path repo-root "src")
    (fs/path repo-root "scripts")
    (fs/path repo-root "web")
    (fs/path repo-root "skills")
    (fs/path home "skills")
    (fs/path home "config.edn")
    (fs/path repo-root "ops" "launchd")]))

(defn run-once!
  [state home]
  (let [after (snapshot (roots home))
        effect (impact (changed-paths @state after))]
    (reset! state after)
    (when (seq (:restart effect)) (apply-impact! effect))))

(defn -main [& args]
  (when-not (= "1" (System/getenv "THESEUS_HOT_RELOAD"))
    (binding [*out* *err*]
      (println "hot reload disabled: set THESEUS_HOT_RELOAD=1 explicitly"))
    (System/exit 2))
  (let [home (or (System/getenv "THESEUS_HOME")
                 (str (fs/path (System/getProperty "user.home") "theseus")))
        interval (Long/parseLong (or (first args) "1000"))
        debounce-ms (max 250 (min 1000 (quot interval 2)))
        state (atom (snapshot (roots home)))]
    (println (str "theseus hot reload enabled; interval-ms=" interval))
    (flush)
    (loop []
      (Thread/sleep interval)
      (try
        (let [after (snapshot (roots home))
              changed (changed-paths @state after)]
          (when (seq changed)
            ;; Settle burst saves before kicking launchd.
            (Thread/sleep debounce-ms)
            (let [settled (snapshot (roots home))
                  effect (impact (changed-paths @state settled))]
              (reset! state settled)
              (when (seq (:restart effect))
                (apply-impact! effect)))))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "hot reload error: " (.getMessage e)))
            (flush))))
      (recur))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
