(ns irc.libera.chat.bayaz.core-test
  (:require [clojure.test :as t]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.operation.util :as operation.util]))

(t/deftest message->operation
  (let [operation {:command "quiet"
                   :mention? false
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
