(ns probe-reload
  (:require [bb-agent.hot-reload :as reload]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(defn- pid [label]
  (some-> (process/shell {:out :string} "launchctl" "print"
                         (str "gui/" (str/trim (:out (process/shell {:out :string} "id" "-u"))) "/" label))
          :out
          (str/split-lines)
          (->> (keep #(second (re-find #"^\s*pid = (\d+)" %))) first)))

(defn- assert! [ok message]
  (when-not ok (throw (ex-info message {}))))

(defn -main []
  (assert! (= #{:web :telegram}
             (:restart (reload/impact ["/repo/src/bb_agent/web.clj"])))
           "source change did not target both long-lived services")
  (assert! (empty? (:restart (reload/impact ["/repo/ops/launchd/web.plist"])))
           "plist change must not be auto-kicked")
  (let [web "com.theseus.web"
        before (pid web)
        asset (str (fs/path (System/getProperty "user.dir") "web" "js" "main.js"))]
    (assert! (some? before) "web service has no launchd pid")
    (process/shell "touch" asset)
    (loop [n 0]
      (let [after (pid web)]
        (cond
          (and after (not= before after))
          (println (str "reload probe: web pid " before " -> " after))
          (= n 10)
          (throw (ex-info "web did not restart after asset change" {:before before :after after}))
          :else (do (Thread/sleep 500) (recur (inc n)))))))
  (loop [n 0]
    (let [r (process/shell {:out :string :err :string :continue true}
                            "curl" "-fsS" "-o" "/dev/null"
                            "-w" "%{http_code}" "http://127.0.0.1:8787/")]
      (if (= "200" (str/trim (:out r)))
        (println "reload probe: PASS")
        (if (= n 20)
          (throw (ex-info "web health was not HTTP 200" {:result r}))
          (do (Thread/sleep 500) (recur (inc n))))))))

(-main)
