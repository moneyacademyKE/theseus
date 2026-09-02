(ns bb-agent.telegram-extract
  "Bounded, optional text extraction for persisted Telegram attachments.

   Extraction is deliberately inert: it reads an already-authorized local
   file, never interprets its contents, and returns nil when no safe reader is
   available."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def ^:private default-max-chars 20000)
(def ^:private max-source-bytes (* 20 1024 1024))
(def ^:private text-extensions
  #{"txt" "text" "md" "markdown" "csv" "tsv" "log" "json" "xml"
    "edn" "clj" "cljs" "bb" "yaml" "yml"})

(defn- extension
  [path]
  (some-> (fs/file-name path)
          str
          (str/lower-case)
          (str/split #"\.")
          last))

(defn- mime-type
  [{:keys [mime-type]}]
  (some-> mime-type str str/lower-case))

(defn- source-ok?
  [path]
  (and path
       (fs/regular-file? path)
       (<= (long (fs/size path)) max-source-bytes)))

(defn- read-text
  [path]
  (try
    (slurp (str path) :encoding "UTF-8")
    (catch Exception _ nil)))

(defn- xml-unescape
  [text]
  (-> (or text "")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")
      (str/replace #"&#(\\d+);" (fn [[_ n]]
                                  (str (char (Integer/parseInt n)))))
      (str/replace #"&#x([0-9a-fA-F]+);" (fn [[_ n]]
                                             (str (char (Integer/parseInt n 16)))))))

(defn- zip-entry-bytes
  [path entry-name]
  (with-open [zip (java.util.zip.ZipFile. (str path))]
    (when-let [entry (.getEntry zip entry-name)]
      (let [size (.getSize entry)]
        (when (or (neg? size) (<= size max-source-bytes))
          (with-open [input (.getInputStream zip entry)
                      output (java.io.ByteArrayOutputStream.)]
            (let [buffer (byte-array 8192)]
              (loop [total 0]
                (let [read (.read input buffer)]
                  (cond
                    (neg? read) (.toByteArray output)
                    (> (+ total read) max-source-bytes) nil
                    :else (do
                            (.write output buffer 0 read)
                            (recur (+ total read)))))))))))))

(defn- docx-text
  [path]
  (when-let [bytes (zip-entry-bytes path "word/document.xml")]
    (let [xml (String. ^bytes bytes "UTF-8")
          paragraphs (re-seq #"(?s)<w:p\b[^>]*>(.*?)</w:p>" xml)
          text (->> paragraphs
                    (map (fn [[_ paragraph]]
                           (->> (re-seq #"(?s)<w:t\b[^>]*>(.*?)</w:t>" paragraph)
                                (map second)
                                (apply str)
                                xml-unescape)))
                    (remove str/blank?)
                    (str/join "\n"))]
      (when-not (str/blank? text) text))))

(defn- command-output
  [command args]
  (try
    (let [result @(process/process (into [command] args)
                                   {:out :string :err :string :continue true})]
      (when (zero? (:exit result)) (:out result)))
    (catch Exception _ nil)))

(defn- pdf-text
  [path]
  (or (command-output "pdftotext" ["-layout" (str path) "-"])
      (command-output "mutool" ["draw" "-F" "txt" "-o" "-" (str path)])))

(defn- raw-text
  [item]
  (let [path (:path item)
        ext (extension path)
        mime (mime-type item)]
    (cond
      (= :photo (:kind item)) nil
      (or (= "docx" ext)
          (= "application/vnd.openxmlformats-officedocument.wordprocessingml.document" mime))
      (docx-text path)
      (or (= "pdf" ext) (= "application/pdf" mime))
      (pdf-text path)
      (or (contains? text-extensions ext)
          (str/starts-with? (or mime "") "text/"))
      (read-text path)
      :else nil)))

(defn- bounded
  [text max-chars]
  (let [text (str text)
        limit (max 1 (long (or max-chars default-max-chars)))]
    (if (<= (count text) limit)
      text
      (str (subs text 0 (max 0 (- limit (count "[truncated]"))))
           "[truncated]"))))

(defn extract-text
  "Extract bounded text from a persisted attachment descriptor.
   Returns a string or nil when the file is unsupported/unreadable."
  ([item] (extract-text item {}))
  ([item {:keys [max-chars]}]
   (when (source-ok? (:path item))
     (some-> (raw-text item) (bounded max-chars)))))
