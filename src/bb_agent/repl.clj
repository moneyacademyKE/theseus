(ns bb-agent.repl
  "REPL-driven development engine and mechanical gates for Theseus.

   Zero new dependencies:
   - `edamame.core` (built into Babashka) validates Clojure syntax/paren balance
     before any `.clj`/`.cljc`/`.cljs`/`.bb` write hits disk.
   - `bencode.core` (built into Babashka) speaks native nREPL over TCP whenever
     a `.nrepl-port` file is active in `cwd`, falling back seamlessly to a
     bounded `bb -e` subprocess when no nREPL server is running."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.tool.common :as common]
            [bencode.core :as bencode]
            [clojure.string :as str]
            [edamame.core :as edamame]))

(def ^:private clojure-exts #{".clj" ".cljc" ".cljs" ".bb"})
(def ^:private gated-tools #{"write_file" "edit_file"})
(def ^:private default-timeout-ms 10000)
(def ^:private max-timeout-ms 30000)
(def ^:private max-output-chars 16000)

(defn clojure-file?
  "True when `path` targets a Clojure/Babashka source file."
  [path]
  (let [s (str/lower-case (str (or path "")))]
    (boolean (some #(str/ends-with? s %) clojure-exts))))

(defn parse-check-clojure
  "Parse all Clojure forms in `source` using Babashka's built-in Edamame reader.
   Returns {:ok? true :forms n} or {:ok? false :error msg}."
  [source]
  (try
    (let [forms (edamame/parse-string-all
                 (str (or source ""))
                 {:all true
                  :auto-resolve {:current 'user}
                  :readers (fn [_] identity)})]
      {:ok? true :forms (count forms)})
    (catch Exception e
      {:ok? false :error (or (ex-message e) (.getName (class e)))})))

(defn- bound-str [s]
  (let [t (str (or s ""))]
    (if (> (count t) max-output-chars)
      (str (subs t 0 (- max-output-chars 16)) "\n...[truncated]")
      t)))

(defn- bytes->str [v]
  (cond
    (bytes? v) (String. ^bytes v "UTF-8")
    (string? v) v
    (nil? v) nil
    :else (str v)))

(defn discover-nrepl-port
  "Read `.nrepl-port` in `dir` (if present and valid integer in 1..65535)."
  [dir]
  (when (and (string? dir) (fs/directory? dir))
    (let [pf (fs/path dir ".nrepl-port")]
      (when (fs/regular-file? pf)
        (try
          (let [port (Integer/parseInt (str/trim (slurp (str pf))))]
            (when (<= 1 port 65535) port))
          (catch Exception _ nil))))))

(defn- nrepl-eval [port code ns-name timeout-ms]
  (let [started (System/nanoTime)]
    (try
      (with-open [sock (java.net.Socket.)]
        (.connect sock (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
        (.setSoTimeout sock (int timeout-ms))
        (let [in (java.io.PushbackInputStream. (.getInputStream sock))
              out (.getOutputStream sock)
              req (cond-> {"op" "eval" "code" code}
                    (and (string? ns-name) (not (str/blank? ns-name)))
                    (assoc "ns" ns-name))]
          (bencode/write-bencode out req)
          (.flush out)
          (loop [stdout [] stderr [] val nil ret-ns nil err nil]
            (let [msg (bencode/read-bencode in)
                  out-s (bytes->str (get msg "out"))
                  err-s (bytes->str (get msg "err"))
                  val-s (bytes->str (get msg "value"))
                  ns-s (bytes->str (get msg "ns"))
                  ex-s (bytes->str (get msg "ex"))
                  statuses (set (map bytes->str (get msg "status")))
                  stdout' (if out-s (conj stdout out-s) stdout)
                  stderr' (if err-s (conj stderr err-s) stderr)
                  val' (or val-s val)
                  ret-ns' (or ns-s ret-ns)
                  err' (or ex-s err)]
              (if (contains? statuses "done")
                (let [ms (long (/ (- (System/nanoTime) started) 1000000))]
                  (if err'
                    {:ok? false :mode :nrepl :port port :ms ms
                     :error (bound-str (str err' (when (seq stderr') (str ": " (apply str stderr')))))
                     :stdout (bound-str (apply str stdout'))
                     :stderr (bound-str (apply str stderr'))}
                    {:ok? true :mode :nrepl :port port :ms ms
                     :ns (or ret-ns' ns-name "user")
                     :value (bound-str (or val' "nil"))
                     :stdout (bound-str (apply str stdout'))
                     :stderr (bound-str (apply str stderr'))}))
                (recur stdout' stderr' val' ret-ns' err'))))))
      (catch Exception e
        {:ok? false :mode :nrepl :port port :connect-error? true
         :error (or (ex-message e) (.getName (class e)))}))))

(def ^:private bb-val-marker "__THESEUS_REPL_VAL__:")

(defn- bb-eval [code ns-name cwd timeout-ms]
  (let [started (System/nanoTime)
        dir (if (and (string? cwd) (fs/directory? cwd)) cwd (str (config/home)))
        ns-prefix (if (and (string? ns-name) (not (str/blank? ns-name)))
                    (str "(try (require '" ns-name ") (in-ns '" ns-name ") (catch Exception _ nil)) ")
                    "")
        wrapper (str "(let [res (do " ns-prefix code ")] "
                     "(println) (print " (pr-str bb-val-marker) ") (pr res))")]
    (try
      (let [proc (p/process ["bb" "-e" wrapper]
                            {:dir dir :out :string :err :string :continue true})
            res (deref proc timeout-ms ::timeout)
            ms (long (/ (- (System/nanoTime) started) 1000000))]
        (if (= ::timeout res)
          (do (p/destroy-tree proc)
              {:ok? false :mode :bb :ms ms :error (str "REPL eval timed out after " timeout-ms "ms")})
          (if (zero? (:exit res))
            (let [raw-out (str (:out res))
                  idx (str/last-index-of raw-out bb-val-marker)
                  stdout (if idx (str/trimr (subs raw-out 0 idx)) raw-out)
                  val-str (if idx (subs raw-out (+ idx (count bb-val-marker))) "nil")]
              {:ok? true :mode :bb :ms ms
               :ns (or ns-name "user")
               :value (bound-str (str/trim val-str))
               :stdout (bound-str stdout)
               :stderr (bound-str (:err res))})
            {:ok? false :mode :bb :ms ms
             :error (bound-str (or (not-empty (str/trim (str (:err res))))
                                   (not-empty (str/trim (str (:out res))))
                                   (str "bb exited with status " (:exit res))))
             :stdout (bound-str (:out res))
             :stderr (bound-str (:err res))})))
      (catch Exception e
        {:ok? false :mode :bb
         :error (or (ex-message e) (.getName (class e)))}))))

(defn eval-code!
  "Evaluate Clojure `code`. Uses live `.nrepl-port` over bencode when present
   and reachable in `cwd`, otherwise falls back to bounded `bb -e`."
  [{:keys [code ns cwd timeout-ms nrepl-port]}]
  (if (str/blank? (str (or code "")))
    {:ok? false :error "repl_eval requires non-blank :code"}
    (let [check (parse-check-clojure code)]
      (if-not (:ok? check)
        {:ok? false :error (str "Clojure syntax error: " (:error check))}
        (let [t-ms (min max-timeout-ms (max 500 (long (or timeout-ms default-timeout-ms))))
              port (or (when (integer? nrepl-port) nrepl-port)
                       (discover-nrepl-port cwd))]
          (if port
            (let [r (nrepl-eval port code ns t-ms)]
              (if (:connect-error? r)
                (bb-eval code ns cwd t-ms)
                r))
            (bb-eval code ns cwd t-ms)))))))

(defn repl-gate-blocked?
  "True when `:repl/enforce` is enabled, `tool-name` is a mutating file tool
   targeting a Clojure source file, and no successful `repl_eval` has run yet
   in this turn."
  [cfg tool-name path]
  (boolean
   (and (:repl/enforce cfg)
        (contains? gated-tools tool-name)
        (clojure-file? path)
        (let [evals (:repl/evals cfg)]
          (or (nil? evals) (zero? (long @evals)))))))

(defn run-repl-tool! [args]
  (let [res (eval-code! args)]
    (if (:ok? res)
      (common/ok-result "repl_eval" (dissoc res :ok?))
      (common/error-result "repl_eval" (:error res) (dissoc res :ok? :error)))))

(def handlers
  {"repl_eval" run-repl-tool!})
