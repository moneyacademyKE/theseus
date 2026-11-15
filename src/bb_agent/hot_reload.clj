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

(defn- regular-files [root]
  (if (fs/directory? root)
    (->> (file-seq (fs/file root))
         (filter #(.isFile ^java.io.File %)))
    (if (fs/regular-file? root) [(fs/file root)] [])))

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
     :restart (cond-> #{ }
                (or (some source? paths) (some config? paths))
                (conj :web :telegram)
                (some web? paths) (conj :web))
     :manual-plists (vec (filter plist? paths))}))

(defn- uid []
  (str/trim (:out (process/shell {:out :string} "id" "-u"))))

(defn kick! [label]
  (let [result (process/shell {:out :string :err :string :continue true}
                              "launchctl" "kickstart" "-k"
                              (str "gui/" (uid) "/" label))]
    (when-not (zero? (:exit result))
      (throw (ex-info "launchctl kickstart failed" {:label label
                                                      :result result})))
    label))

(defn apply-impact! [effect]
  (doseq [service (:restart effect)]
    (kick! (get service-labels service)))
  effect)

(defn- roots [home]
  [(fs/path home "theseus" "src")
   (fs/path home "theseus" "scripts")
   (fs/path home "theseus" "web")
   (fs/path home "theseus" "skills")
   (fs/path home "config.edn")
   (fs/path home "theseus" "ops" "launchd")])

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
        state (atom (snapshot (roots home)))]
    (println (str "theseus hot reload enabled; interval-ms=" interval))
    (flush)
    (loop []
      (Thread/sleep interval)
      (try
        (run-once! state home)
        (catch Exception e
          (binding [*out* *err*]
            (println (str "hot reload error: " (.getMessage e)))
            (flush))))
      (recur))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
