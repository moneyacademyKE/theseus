(ns bb-agent.telegram-voice
  "Honest voice-note handling. Audio persists inertly like any attachment;
   transcription is attempted only through a configured speech-to-text
   binary ([:telegram :stt-bin], else whisper / whisper-cpp on PATH) and
   is reported truthfully when unavailable or failing. The transcript is
   whatever the binary prints on stdout; absence and failure are data,
   never fabricated text."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]))

(def default-max-chars 8000)
(def default-timeout-ms 120000)
(def default-max-duration-secs 120)

(def ^:private default-bins ["whisper" "whisper-cpp"])

(defn- default-resolve-bin
  [cfg]
  (let [configured (str/trim (str (:stt-bin cfg)))]
    (or (when (seq configured) configured)
        (some #(let [found (fs/which %)] (when found (str found)))
              default-bins))))

(defn transcribe
  "Attempt to transcribe one persisted audio attachment. Returns
   {:transcribed? true :text s} or {:transcribed? false :reason k} with
   :reason :no-stt-tool | :duration-exceeded | :failed (plus :detail on failure).
   Text is bounded by :max-chars. `exec` and `resolve-bin` are injectable for
   deterministic tests."
  [item {:keys [resolve-bin exec max-chars timeout-ms max-duration-secs]
         :or {max-chars default-max-chars timeout-ms default-timeout-ms}}]
  (let [max-dur (or max-duration-secs (:stt-max-duration-secs item) default-max-duration-secs)
        duration (:duration item)]
    (if (and (number? duration) (> duration max-dur))
      {:transcribed? false
       :reason :duration-exceeded
       :duration duration
       :max-duration-secs max-dur}
      (let [bin (if resolve-bin (resolve-bin item) (default-resolve-bin item))]
        (if (str/blank? (str bin))
          {:transcribed? false :reason :no-stt-tool}
          (try
            (let [{:keys [exit out err]}
                  (if exec
                    (exec bin [(str (:path item))])
                    (let [done (proc/shell {:out :string :err :string
                                            :timeout timeout-ms :continue true}
                                           (str bin) (str (:path item)))]
                      {:exit (:exit done) :out (str (:out done)) :err (str (:err done))}))
                  text (str/trim (str out))]
              (if (and (number? exit) (zero? (long exit)) (seq text))
                {:transcribed? true
                 :text (subs text 0 (min (count text) max-chars))}
                {:transcribed? false :reason :failed
                 :detail (str/trim (str "exit " (pr-str exit)
                                        (when (seq (str err)) (str " " (str/trim (str err))))))}))
            (catch Exception e
              {:transcribed? false :reason :failed
               :detail (or (ex-message e) (.getName (class e)))})))))))

(defn annotation
  "One bounded context line for the turn input: the transcript, or the
   honest reason there is none."
  ([telegram-cfg item] (annotation telegram-cfg item {}))
  ([telegram-cfg item opts]
   (let [item (assoc item
                     :stt-bin (:stt-bin telegram-cfg)
                     :stt-max-duration-secs (:stt-max-duration-secs telegram-cfg))
         result (transcribe item opts)]
     (if (:transcribed? result)
       (str "[Voice note transcript: " (:text result) "]")
       (case (:reason result)
         :duration-exceeded
         (str "[Voice note: duration exceeds limit (" (:duration result) "s > "
              (:max-duration-secs result) "s); audio saved but not transcribed]")
         :no-stt-tool
         "[Voice note: no speech-to-text tool available on this machine; audio saved but not transcribed]"
         "[Voice note: transcription failed; audio saved but no transcript was produced]")))))
