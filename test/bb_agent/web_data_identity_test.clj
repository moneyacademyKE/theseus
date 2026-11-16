(ns bb-agent.web-data-identity-test
  (:require [bb-agent.web-data :as web-data]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

(deftest checkout-identity-rejects-non-repository
  (let [root (str (fs/create-temp-dir {:prefix "checkout-identity-"} ))]
    (try
      (is (= {:root nil :git nil} (web-data/checkout-identity root)))
      (finally (fs/delete-tree root)))))

(deftest checkout-identity-reports-real-checkout
  (let [root (str (fs/canonicalize "."))
        identity (web-data/checkout-identity root)]
    (is (= root (:root identity)))
    (is (re-matches #"[0-9a-f]{7,}" (:git identity)))))
