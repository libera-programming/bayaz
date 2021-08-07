(ns irc.libera.chat.bayaz.util-test
  (:require [clojure.test :as t]
            [irc.libera.chat.bayaz.util :as util]))

(t/deftest size->human-readable
  (t/is (= "0 B" (util/size->human-readable 0)))
  (t/is (= "999 B" (util/size->human-readable 999)))
  (t/is (= "1.0 kB" (util/size->human-readable 1000)))
  (t/is (= "1.8 kB" (util/size->human-readable 1824)))
  (t/is (= "57.0 kB" (util/size->human-readable (* 57 1000))))
  (t/is (= "1.0 MB" (util/size->human-readable (* 1000 1000))))
  (t/is (= "57.0 MB" (util/size->human-readable (* 57 1000 1000))))
  (t/is (= "1.0 GB" (util/size->human-readable (* 1000 1000 1000))))
  (t/is (= "57.0 GB" (util/size->human-readable (* 57 1000 1000 1000))))
  (t/is (= "1.0 TB" (util/size->human-readable (* 1000 1000 1000 1000))))
  (t/is (= "57.0 TB" (util/size->human-readable (* 57 1000 1000 1000 1000))))
  (t/is (= "1.0 PB" (util/size->human-readable (* 1000 1000 1000 1000 1000))))
  (t/is (= "57.0 PB" (util/size->human-readable (* 57 1000 1000 1000 1000 1000))))
  (t/is (= "1.0 EB" (util/size->human-readable (*' 1000 1000 1000 1000 1000 1000))))
  (t/is (= "57.0 EB" (util/size->human-readable (*' 57 1000 1000 1000 1000 1000 1000)))))
