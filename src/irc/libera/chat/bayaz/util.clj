(ns irc.libera.chat.bayaz.util
  (:import [java.io InputStream ByteArrayOutputStream]
           [java.text StringCharacterIterator]))

(def max-message-length 256)
(defn truncate [text max-length]
  (let [length (count text)]
    (cond
      (nil? text)
      ""
      (<= length max-length)
      text
      :else
      (str (subs text 0 (dec max-length)) "…"))))

; Ported from: https://stackoverflow.com/a/3758880
(defn size->human-readable
  "Converts a size, in byts, to a human readable format, such as 0 B, 1.5 kB, 10 GB, etc."
  [size-in-bytes]
  (if (and (< -1000 size-in-bytes) (< size-in-bytes 1000))
    (str size-in-bytes " B")
    (let [ci (StringCharacterIterator. "kMGTPE")
          size (reduce (fn [acc _]
                             (if-not (or (<= acc -999950) (<= 999950 acc))
                               (reduced acc)
                               (do
                                 (.next ci)
                                 (/ acc 1000))))
                           size-in-bytes
                           (repeat nil))]
      (format "%.1f %cB" (float (/ size 1000)) (.current ci)))))

(defn parse-int [int-str default]
  (try
    (Integer/parseInt int-str)
    (catch Exception _
      default)))

(defn url->domain [url]
  (let [uri (java.net.URI. url)]
    (.getHost uri)))

(def stream-read-buffer-size (* 10 1024))
(defn read-stream-str!
  "Reads up to `max-size` from the given stream. This allows us to download web pages while avoiding
   DOS attacks designed to make us download way too much. Returns a UTF-8 string of the downloaded
   data."
  [^InputStream stream max-size]
  (with-open [output-stream (ByteArrayOutputStream.)]
    (let [buffer (byte-array stream-read-buffer-size)]
      (loop [total-size 0]
        (let [size (.read stream buffer 0 stream-read-buffer-size)
              new-total-size (+ total-size size)]
          (cond
            (or (<= max-size new-total-size) (-> size pos? not))
            (.toString output-stream "UTF-8")

            (pos? size)
            (do
              (.write output-stream buffer 0 size)
              (recur new-total-size))))))))
