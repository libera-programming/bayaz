(ns irc.libera.chat.bayaz.core-test
  (:require [clojure.test :as t]
            [irc.libera.chat.bayaz.core :as core]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

(t/deftest admin?
  (t/testing "valid"
    (t/is (core/admin? "jeaye")))

  (t/testing "invalid"
    (t/is (not (core/admin? "jeaye-imposter"))))

  (t/testing "not registered"
    (t/is (not (core/admin? nil)))))

; TODO: Test public messages (nil command)
(t/deftest message->operation
  (let [operation {:command "quiet"
                   :mention? false
                   :parts ["quiet" "jeaye" "being mean" "10m"]
                   :args ["jeaye" "being mean" "10m"]}
        operation+mention (assoc operation :mention? true)]
    (t/testing "whitespace"
      (t/is (= operation (-> "   quiet     jeaye   \"being mean\"     10m  "
                             operation.util/message->operation)))
      (t/is (= operation (-> "\tquiet jeaye \"being mean\" 10m"
                             operation.util/message->operation))))

    (t/testing "nick prefix"
      (t/is (= operation+mention (-> (str (:nick @state/global-config)
                                          ": quiet jeaye \"being mean\" 10m")
                                     operation.util/message->operation)))
      (t/is (= operation+mention (-> (str (:nick @state/global-config)
                                          " quiet jeaye \"being mean\" 10m")
                                     operation.util/message->operation))))))
