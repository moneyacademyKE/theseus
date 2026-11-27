(ns bb-agent.gaslighting
  "Gaslighting-preamble detection and stripping (ported from OpenCrabs gaslighting.rs).
   Catches assistant refusal/broken-tool preambles emitted alongside real tool
   calls that actually succeeded (e.g. 'tools aren't responding', 'upload it to
   imgur', 'isn't registered in my current environment') and strips them before
   reaching the user."
  (:require [clojure.string :as str]))

(def ^:private gaslighting-phrases
  ["tools aren't responding"
   "tools are not responding"
   "tools are flaky"
   "tools appear to be"
   "tools appear broken"
   "tools appear unavailable"
   "appear to be unavailable"
   "tools are unavailable"
   "tools are currently unavailable"
   "tools are disabled"
   "tools are not loading"
   "tools are not available"
   "isn't currently available"
   "is not currently available"
   "not currently available"
   "tool isn't currently"
   "tool is not currently"
   "vision tool isn't"
   "vision tool is not"
   "despite being in my tool list"
   "despite being in the tool list"
   "isn't actually registered"
   "is not actually registered"
   "not actually registered"
   "isn't registered"
   "isn't in the registry"
   "not in the registry"
   "mismatch between the advertised"
   "runtime hiccup"
   "provider hiccup"
   "provider glitch"
   "can't execute the tool"
   "cannot execute the tool"
   "unable to execute the tool"
   "tool execution failed before it started"
   "try uploading it again"
   "try uploading the image again"
   "upload the screenshot to a public"
   "upload the image to a public"
   "public url (imgur"
   "(imgur, github"
   "don't have access to a working"
   "do not have access to a working"
   "tool isn't available in my"
   "tool is not available in my"
   "isn't available in my current environment"
   "not available in my current environment"
   "in my current environment"
   "tools are acting up"
   "tools are acting weird"
   "tool layer is acting"])

(defn has-gaslighting-preamble?
  "True when `text` contains a known refusal/broken-tool gaslighting phrase."
  [text]
  (if (or (nil? text) (str/blank? (str text)))
    false
    (let [s (str/lower-case (str text))]
      (boolean (some #(str/includes? s %) gaslighting-phrases)))))

(defn strip-gaslighting-preamble
  "Strip leading paragraphs that contain known gaslighting refusal phrases.
   Returns the cleaned remainder, or nil if the entire text was gaslighting."
  [text]
  (when (and (string? text) (not (str/blank? text)))
    (if-not (has-gaslighting-preamble? text)
      text
      (let [paras (str/split (str/trim text) #"\r?\n\r?\n+")
            kept (drop-while has-gaslighting-preamble? paras)]
        (when (seq kept)
          (str/join "\n\n" kept))))))
