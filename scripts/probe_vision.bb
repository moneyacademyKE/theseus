#!/usr/bin/env bb
;; probe_vision.bb — is the gateway actually serving the vision model
;; (zai/glm-4.6v)? Reads the API key from ~/theseus/config.edn at runtime;
;; the key never appears in any output. The probe PNG (1x1 red) is built
;; byte-by-byte here — CRCs computed, not hand-copied — so the request is
;; always a structurally valid image.
;; Usage: bb scripts/probe_vision.bb
;; Exit 0 = correct image answer; 1 = unexpected response; 2 = transport error.

(require '[cheshire.core :as json])

(import '[java.io ByteArrayOutputStream]
         '[java.util.zip DeflaterOutputStream CRC32])

(def cfg (clojure.edn/read-string (slurp (str (System/getProperty "user.home") "/theseus/config.edn"))))
(def base (get-in cfg [:providers :openai-compatible :base-url]))
(def api-key (get-in cfg [:providers :openai-compatible :api-key]))
(def model (or (:vision-model cfg) "zai/glm-4.6v"))

;; ---------- build a valid 1x1 red PNG from scratch ----------
(defn- be4 ^bytes [n]
  (byte-array [(unchecked-byte (bit-and (bit-shift-right n 24) 0xFF))
               (unchecked-byte (bit-and (bit-shift-right n 16) 0xFF))
               (unchecked-byte (bit-and (bit-shift-right n 8) 0xFF))
               (unchecked-byte (bit-and n 0xFF))]))

(defn- chunk ^bytes [^String type ^bytes data]
  (let [t (.getBytes type "US-ASCII")
        crc (doto (CRC32.) (.update t) (.update data))
        out (ByteArrayOutputStream.)]
    (.write out (be4 (alength data)))
    (.write out t)
    (.write out data)
    (.write out (be4 (.getValue crc)))
    (.toByteArray out)))

(defn- deflate ^bytes [^bytes data]
  (let [out (ByteArrayOutputStream.)
        dos (DeflaterOutputStream. out)]
    (.write dos data)
    (.close dos)
    (.toByteArray out)))

(def png-b64
  (let [sig (byte-array (map unchecked-byte [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]))
        ihdr (chunk "IHDR" (byte-array (concat (seq (be4 1)) (seq (be4 1))
                                               [(unchecked-byte 8) (unchecked-byte 2)
                                                (unchecked-byte 0) (unchecked-byte 0) (unchecked-byte 0)])))
        scanline (byte-array (map unchecked-byte [0x00 0xFF 0x00 0x00])) ; filter 0 + RGB red
        idat (chunk "IDAT" (deflate scanline))
        iend (chunk "IEND" (byte-array 0))
        all (ByteArrayOutputStream.)]
    (doseq [b [sig ihdr idat iend]] (.write all b))
    (.encodeToString (java.util.Base64/getEncoder) (.toByteArray all))))

(when-not (re-find #"^iVBOR" png-b64)
  (println "self-check failed: PNG signature wrong") (System/exit 2))

;; ---------- ask the gateway ----------
(def body
  (json/generate-string
   {:model model
    :messages [{:role "user"
                :content [{:type "text" :text "What is the main color of this image? Reply with one word."}
                          {:type "image_url"
                           :image_url {:url (str "data:image/png;base64," png-b64)}}]}]
    :max_tokens 256}))

(try
  (let [{:keys [status body error]} @(org.httpkit.client/post
                                (str base "/chat/completions")
                                {:headers {"Authorization" (str "Bearer " api-key)
                                           "Content-Type" "application/json"}
                                 :body body
                                 :timeout 90000})]
    (println "model:" model)
    (println "HTTP" status)
    (if (= 200 status)
      (let [parsed (json/parse-string body true)
            choice (get-in parsed [:choices 0])
            reply (get-in choice [:message :content])]
        (println "finish_reason:" (:finish_reason choice))
        (println "reply:" (pr-str reply))
        (System/exit (if (and (string? reply) (re-find #"(?i)\bred\b" reply)) 0 1)))
      (do (println (if error (str "transport error:" (some-> error class .getName))
                       "vision request did not return HTTP 200"))
          (System/exit (if error 2 1)))))
  (catch Exception e
    (println "EXCEPTION:" (.getMessage e))
    (System/exit 2)))
