(ns irc.libera.chat.bayaz.core-test
  (:require [clojure.test :as t]
            [irc.libera.chat.bayaz.core :as core]))

(t/deftest message->operation
  (let [operation {:command "quiet"
                   :args ["jeaye" "being mean" "10m"]}]
    (t/testing "whitespace"
      (t/is (= operation (core/message->operation "   quiet     jeaye   \"being mean\"     10m  ")))
      (t/is (= operation (core/message->operation "\tquiet jeaye \"being mean\" 10m"))))

    (t/testing "nick prefix"
      (t/is (= operation (core/message->operation (str (:nick @core/global-config) ": quiet jeaye \"being mean\" 10m"))))
      (t/is (= operation (core/message->operation (str (:nick @core/global-config) " quiet jeaye \"being mean\" 10m")))))))
