#!/usr/bin/env bb
;; Live A2A probe: Theseus client → OpenCrabs bridge at 127.0.0.1:18790.
;; Token: ~/theseus/state/a2a-opencrabs-token (mode 600), never printed.
;; Usage: bb scripts/probe_a2a_client.bb        → discovery + auth gate + ping
;;        bb scripts/probe_a2a_client.bb borrow → also borrow a PDF document
(require '[babashka.fs :as fs]
          '[babashka.process :as process]
          '[bb-agent.a2a-client :as a2a]
          '[clojure.string :as str])

(def base-url "http://127.0.0.1:18790")
(def token-path (str (System/getenv "HOME") "/theseus/state/a2a-opencrabs-token"))
(def pdftotext-bin
  (or (when (fs/executable? "/opt/homebrew/bin/pdftotext") "/opt/homebrew/bin/pdftotext")
      "pdftotext"))

(defn- token []
  (when (fs/regular-file? token-path)
    (str/trim (slurp token-path))))

(defn- head [s n]
  (subs (str s) 0 (min n (count (str s)))))

;; 1. discovery (public card, no auth)
(let [card (a2a/agent-card base-url)]
  (if-not (:ok? card)
    (do (println "FAIL discovery:" (:error card)) (System/exit 1))
    (println "discovery ok:" (get-in card [:card :name])
             "| version" (get-in card [:card :version]))))

;; 2. unauthenticated send must be refused
(let [r (a2a/send-message {:base-url base-url :text "ping" :token nil})]
  (println "unauth send -> HTTP" (:status r))
  (when-not (= 401 (:status r))
    (println "WARN: expected 401 without token")))

;; 3. authenticated ping roundtrip
(let [t (token)]
  (if (or (nil? t) (str/blank? t))
    (do (println "FAIL: no token file at" token-path) (System/exit 1))
    (let [r (a2a/run-task {:base-url base-url
                           :text "Reply with exactly the single word: pong"
                           :token t
                           :timeout-ms 240000
                           :poll-ms 2000})]
      (println "ping -> state:" (:state r) "| error:" (:error r))
      (println "reply:" (pr-str (head (:text r) 200)))
      (when-not (:ok? r) (System/exit 1))
      (println "PING OK"))))

;; 4. optional: borrow a generated document through the bridge
(when (= "borrow" (first *command-line-args*))
  (let [pdf "/Users/moe/theseus/scratch/a2a-borrow.pdf"
        r (a2a/run-task {:base-url base-url
                         :text (str "Generate a one-page PDF file at " pdf " "
                                    "with title 'A2A borrow proof' and body "
                                    "'Theseus borrowed OpenCrabs document generation via A2A. "
                                    "The marker sentence is: pack my box with five dozen liquor jugs.' "
                                    "Use your generate_document tool. Then reply with the "
                                    "absolute file path and nothing else.")
                         :token (token)
                         :timeout-ms 300000
                         :poll-ms 3000})]
    (println "borrow -> state:" (:state r) "| error:" (:error r))
    (println "reply:" (pr-str (head (:text r) 300)))
    (if-not (:ok? r)
      (System/exit 1)
      (if-not (fs/exists? pdf)
        (do (println "BORROW INCOMPLETE: reply ok but no file at" pdf)
            (System/exit 1))
        (let [out (:out @(process/process [pdftotext-bin "-layout" pdf "-"]
                                          {:out :string :err :string}))]
          (println "pdf exists | extracted head:" (pr-str (head (str/trim (str out)) 160)))
          (let [marker? (str/includes? (str out) "five dozen liquor jugs")]
            (println "marker sentence present:" marker?)
            (println (if marker? "BORROW OK" "BORROW UNVERIFIED"))
            (System/exit (if marker? 0 1))))))))
