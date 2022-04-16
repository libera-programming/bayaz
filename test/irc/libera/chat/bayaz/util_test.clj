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

(t/deftest relative-time-offset
  (t/is (= "just now" (util/relative-time-offset 0 0)))
  (t/is (= "just now" (util/relative-time-offset 999 0)))
  (t/is (= "1m 12s ago" (util/relative-time-offset (* 1.2 60000) 0)))
  (t/is (= "1d 4h ago" (util/relative-time-offset (* 1.2 86400000) 0)))
  (t/is (= "11 months 30d ago" (util/relative-time-offset (dec 31557600000) 0)))
  (t/is (= "3y 6 months ago" (util/relative-time-offset (* 3.5 31557600000) 0)))
  (t/is (= "3y 6 months from now" (util/relative-time-offset 0 (* 3.5 31557600000)))))
