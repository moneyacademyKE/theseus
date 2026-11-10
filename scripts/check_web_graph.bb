#!/usr/bin/env bb
;; check_web_graph.bb — resolve web/js module imports against exports.
;;
;; Why: a missing named export is a SyntaxError the browser throws while
;; RESOLING the ES module graph, before any code runs. The deck then
;; shows the static "connecting…" shell forever — no boot, no login
;; overlay, nothing. node --check cannot see this (per-file syntax
;; only) and curl drills never execute JS; only a browser (or this
;; resolver) does. Incident 2026-11-10: media.js imported `put` that
;; api.js never exported — every fresh page load died silently.
;;
;; This checker mimics the resolution step: every import of a known
;; form must resolve to a file that exports the name. Any import or
;; export shape it cannot parse fails LOUDLY — an unparsed form is an
;; audit gap, not a green light. Exit 0 = graph resolves; exit 1 =
;; named problems; exit 2 = checker cannot run.
(require '[clojure.string :as str]
         '[babashka.fs :as fs])

(def root (or (first *command-line-args*) "web"))
(def files (sort (map str (fs/glob (fs/path root "js") "*.js"))))

(when (empty? files)
  (println "check_web_graph: no js files under" root)
  (System/exit 2))

(defn- strip-comments
  "Drop /* */ block and // line comments so prose (a comment that says
  'import' or 'export') can't trip the keyword guards and commented-out
  code can't pose as a real form. `://` (URL scheme) stays intact."
  [src]
  (-> src
      (str/replace #"/\*[\s\S]*?\*/" " ")
      (str/replace #"(?<!:)//[^\n]*" " ")))
(def import-form  #"import\s+\{([\s\S]*?)\}\s+from\s+'\./([A-Za-z0-9_./-]+\.js)'")
(def export-forms [#"export\s+(?:async\s+)?function\s+([A-Za-z0-9_]+)"
                   #"export\s+(?:const|let|var)\s+([A-Za-z0-9_]+)"])

(defn exports-of [src]
  (set (mapcat #(map last (re-seq % src)) export-forms)))

(def table (into {} (for [f files] [f (exports-of (strip-comments (slurp f)))])))

(def problems (atom []))
(def unparsed (atom []))

(doseq [f files
        :let [src (strip-comments (slurp f))
              imports (re-seq import-form src)
              import-keywords (count (re-seq #"\bimport\b" src))
              export-keywords (count (re-seq #"\bexport\b" src))
              export-matches (count (mapcat #(re-seq % src) export-forms))]]
  (when (not= import-keywords (count imports))
    (swap! unparsed conj (str f ": import form I can't parse ("
                              import-keywords " keywords, " (count imports) " matched)")))
  (when (not= export-keywords export-matches)
    (swap! unparsed conj (str f ": export form I can't parse ("
                              export-keywords " keywords, " export-matches " matched)")))
  (doseq [[_ names from] imports
          :let [target (str (fs/parent f) fs/file-separator from)]]
    (if-not (contains? table target)
      (swap! problems conj (str f " imports a missing FILE: " from))
      (doseq [w (remove str/blank? (map str/trim (str/split names #",")))]
        (when-not (contains? (get table target) w)
          (swap! problems conj (str f " imports '" w "' — not exported by " from)))))))

(doseq [u @unparsed]   (println "UNPARSED:" u))
(doseq [p @problems]   (println "MISSING:" p))
(println "web module graph:" (count files) "files,"
         (count @problems) "missing exports,"
         (count @unparsed) "unparsed forms")
(System/exit (if (or (seq @problems) (seq @unparsed)) 1 0))
