(ns axiom.control
  "Operator control files for pause/resume/stop.

  Controls are intentionally tiny filesystem facts under the run workdir so
  humans can intervene with boring tools and status can explain the state
  without talking to a daemon."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn control-dir
  [cfg]
  (io/file (:workdir cfg ".") ".axiom-control"))

(defn control-path
  [cfg kind]
  (io/file (control-dir cfg) (str (name kind))))

(defn ensure-control-dir!
  [cfg]
  (.mkdirs (control-dir cfg)))

(defn request!
  "Write a control request. kind is :pause or :stop. Returns the file path."
  [cfg kind]
  (when-not (#{:pause :stop} kind)
    (throw (ex-info (str "Unknown control request: " kind) {:kind kind})))
  (ensure-control-dir! cfg)
  (let [path (control-path cfg kind)]
    (spit path (str (java.time.Instant/now) "\n"))
    (str path)))

(defn clear!
  "Clear a control request. kind is :pause or :stop. Returns true if removed."
  [cfg kind]
  (let [f (control-path cfg kind)]
    (boolean (and (.exists f) (.delete f)))))

(defn state
  "Read current operator control state. :stop beats :paused."
  [cfg]
  (let [stop?  (.exists (control-path cfg :stop))
        pause? (.exists (control-path cfg :pause))]
    {:state (cond stop? :stop-requested
                  pause? :paused
                  :else :running)
     :pause-file (str (control-path cfg :pause))
     :stop-file (str (control-path cfg :stop))}))

(defn apply-command!
  "Apply a CLI control command and return a printable result map."
  [cfg command]
  (case command
    :pause  {:command :pause :path (request! cfg :pause) :state (:state (state cfg))}
    :resume {:command :resume :cleared? (clear! cfg :pause) :state (:state (state cfg))}
    :stop   {:command :stop :path (request! cfg :stop) :state (:state (state cfg))}
    (throw (ex-info (str "Unknown control command: " command) {:command command}))))

(defn parse-command
  [s]
  (keyword (str/lower-case (str s))))
