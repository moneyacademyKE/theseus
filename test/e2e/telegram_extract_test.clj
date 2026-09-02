(ns e2e.telegram-extract-test
  (:require [babashka.fs :as fs]
            [bb-agent.telegram-extract :as extract]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-file!
  [dir name bytes]
  (let [path (fs/path dir name)]
    (fs/write-bytes path bytes)
    (str path)))

(defn- write-docx!
  [dir name text]
  (let [path (str (fs/path dir name))
        zos (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. path))]
    (try
      (.putNextEntry zos (java.util.zip.ZipEntry. "word/document.xml"))
      (.write zos (.getBytes (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                  "<w:document><w:body>"
                                  "<w:p><w:r><w:t>first paragraph " text "</w:t></w:r></w:p>"
                                  "<w:p><w:r><w:t>second paragraph</w:t></w:r></w:p>"
                                  "</w:body></w:document>")
                             "UTF-8"))
      (.closeEntry zos)
      (finally (.close zos)))
    path))

(deftest extract-text-reads-the-text-family
  (let [dir (fs/create-temp-dir {:prefix "theseus-extract-"})]
    (try
      (testing "markdown with mime type"
        (let [path (temp-file! dir "notes.md" (.getBytes "# Notes\nhello sense" "UTF-8"))]
          (is (= "# Notes\nhello sense"
                 (extract/extract-text {:kind :document :path path :mime-type "text/markdown"})))))
      (testing "plain text by extension when mime is absent"
        (let [path (temp-file! dir "run.log" (.getBytes "line one" "UTF-8"))]
          (is (= "line one" (extract/extract-text {:kind :document :path path})))))
      (testing "extension wins over opaque mime, case-insensitively"
        (let [path (temp-file! dir "DATA.CSV" (.getBytes "a,b\n1,2" "UTF-8"))]
          (is (= "a,b\n1,2"
                 (extract/extract-text {:kind :document :path path
                                        :mime-type "application/octet-stream"})))))
      (finally
        (fs/delete-tree dir)))))

(deftest extract-text-reads-docx-body-text
  (let [dir (fs/create-temp-dir {:prefix "theseus-extract-"})]
    (try
      (let [path (write-docx! dir "report.docx" "quarterly numbers")]
        (is (= "first paragraph quarterly numbers\nsecond paragraph"
               (extract/extract-text {:kind :document :path path
                                      :mime-type "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}))))
      (finally
        (fs/delete-tree dir)))))

(deftest extract-text-degrades-honestly
  (let [dir (fs/create-temp-dir {:prefix "theseus-extract-"})]
    (try
      (testing "binary garbage with a text mime decodes instead of throwing"
        (let [path (temp-file! dir "broken.txt" (byte-array [0xC3 0x28 0xE2 0x80 0x3F]))]
          (is (string? (extract/extract-text {:kind :document :path path
                                              :mime-type "text/plain"})))))
      (testing "pdf stays unread without external tooling"
        (let [path (temp-file! dir "doc.pdf" (.getBytes "%PDF-1.4 fake" "UTF-8"))]
          (is (nil? (extract/extract-text {:kind :document :path path
                                           :mime-type "application/pdf"})))))
      (testing "photos return nil"
        (let [path (temp-file! dir "pic.jpg" (byte-array [0 1 2 3]))]
          (is (nil? (extract/extract-text {:kind :photo :path path
                                           :mime-type "image/jpeg"})))))
      (testing "unknown binary returns nil"
        (let [path (temp-file! dir "blob.bin" (byte-array [0 1 2 3]))]
          (is (nil? (extract/extract-text {:kind :document :path path
                                           :mime-type "application/octet-stream"})))))
      (testing "missing path returns nil"
        (is (nil? (extract/extract-text {:kind :document :path "/no/such/file.txt"}))))
      (finally
        (fs/delete-tree dir)))))

(deftest extract-text-bounds-long-content
  (let [dir (fs/create-temp-dir {:prefix "theseus-extract-"})]
    (try
      (let [path (temp-file! dir "big.txt" (.getBytes (apply str (repeat 500 "x")) "UTF-8"))
            result (extract/extract-text {:kind :document :path path :mime-type "text/plain"}
                                         {:max-chars 100})]
        (is (str/starts-with? result "xxx"))
        (is (str/ends-with? result "[truncated]"))
        (is (< (count result) 120)))
      (finally
        (fs/delete-tree dir)))))
