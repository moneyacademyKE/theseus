(ns bb-agent.release-test
  "V6 pins (bk-5e88): the changelog promotion is the release's soul —
   Unreleased must be present and must become the dated section."
  (:require [clojure.string :as str]
            [bb-agent.release :as release]
            [clojure.test :refer [deftest is testing]]))

(deftest promote-changelog-test
  (testing "Unreleased becomes the dated section; no section = refuse"
    (let [cl "# Changelog\n\n## Unreleased\n\n### Added\n\n- thing\n\n## v0.9.0 - 2026-09-09\n"]
      (is (str/includes? (release/promote-changelog cl "v1.0.0" "2026-09-10")
                         "## v1.0.0 - 2026-09-10"))
      (is (str/includes? (release/promote-changelog cl "v1.0.0" "2026-09-10")
                         "### Added"))
      (is (not (str/includes? (release/promote-changelog cl "v1.0.0" "2026-09-10")
                              "## Unreleased"))))
    (is (nil? (release/promote-changelog "# Changelog\n\n## v0.9.0\n" "v1.0.0" "2026-09-10"))
        "a release without notes is refused")))
