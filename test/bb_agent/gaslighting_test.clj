(ns bb-agent.gaslighting-test
  (:require [bb-agent.gaslighting :as gas]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest gaslighting-preamble-detection-and-strip-test
  (testing "detects known refusal preambles when tools actually ran"
    (is (true? (gas/has-gaslighting-preamble?
                "The tools aren't responding right now, try uploading the image again.")))
    (is (true? (gas/has-gaslighting-preamble?
                "My vision tool isn't registered in my current environment.")))
    (is (false? (gas/has-gaslighting-preamble?
                 "All 12 checks passed and the binary is installed."))))

  (testing "strip-gaslighting-preamble drops refusal lead-in while preserving real answer"
    (let [text "Tools appear to be unavailable in my current environment.\n\nActual finding: port 8790 returned 200 OK."
          cleaned (gas/strip-gaslighting-preamble text)]
      (is (not (str/includes? cleaned "unavailable")))
      (is (str/includes? cleaned "Actual finding: port 8790 returned 200 OK."))))

  (testing "returns nil when entire text is pure gaslighting"
    (is (nil? (gas/strip-gaslighting-preamble
               "Tools are currently unavailable — upload the screenshot to a public url (imgur).")))))
