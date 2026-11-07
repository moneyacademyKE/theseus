#!/usr/bin/env bb
;; set_web_password.bb — set or rotate the Theseus web deck master
;; password. Interactive (typed twice, hidden when the terminal supports
;; it) or piped: printf 'pw\npw\n' | bb scripts/set_web_password.bb.
;; Disk gets only a PBKDF2-SHA256 salt+hash record (owner-only perms);
;; the password itself never lands anywhere in the clear. A rotation
;; drops every live deck session immediately.
;; Exit 0 = set; 1 = too short / entries differ; nothing is written then.

(require '[bb-agent.web-auth :as auth])

(defn- read-secret
  "Hidden entry on a real terminal, plain read-line when piped."
  [prompt]
  (if-let [console (System/console)]
    (String. (.readPassword console "%s" (into-array Object [prompt])))
    (do (print prompt) (flush) (read-line))))

(let [pw  (read-secret "New master password: ")
      pw2 (read-secret "Once more, to confirm: ")]
  (cond
    (or (nil? pw) (< (count (str pw)) 8))
    (do (println "At least 8 characters, please. Nothing changed.")
        (System/exit 1))

    (not= pw pw2)
    (do (println "The two entries differ. Nothing changed.")
        (System/exit 1))

    :else
    (do (auth/set-password! pw)
        (println "Master password set:" (str (auth/password-file)))
        (println "The deck login accepts it now; every old session is gone."))))
