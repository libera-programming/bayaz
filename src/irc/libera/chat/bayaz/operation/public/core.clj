(ns irc.libera.chat.bayaz.operation.public.core
  (:require [clojure.string :as string]
            [clj-http.client :as http]
            [clojure.core.memoize :as memo]
            [reaver]
            [irc.libera.chat.bayaz.state :as state]
            [irc.libera.chat.bayaz.util :as util]))

(defmulti process!
  (fn [op]
    (:command op)))

(defmethod process! "bayaz"
  [op]
  (when (state/feature-enabled? :bayaz-command)
    (.respondWith (:event op)
                  (str "I'm a Clojure bot. My source is here: https://github.com/libera-programming/bayaz"))))

(defmethod process! "ops"
  [op]
  (when (state/feature-enabled? :ops-command)
    (.respondWith (:event op)
                  (str "Admins are: " (string/join ", " (:admins @state/global-config))))))

(defmethod process! :default
  [_op]
  :not-handled)

(def max-content-size (* 512 1000)) ; 512 kB
(defn fetch-url!*
  "Fetches the URL, first with a HEAD to detect the content type and existence, and then with a GET
   in the case of an HTML type.

   Situations:
   * If HEAD results in too many redirects, do nothing
   * If HEAD results in a non-200, do nothing
   * If HEAD says the content type is non-HTML, return the following
     * If the final domain is different from the original, assoc it
     * If the content type is present, assoc it
     * If the content length is present, assoc it
   * Otherwise, do a GET and read the body as a stream, with a max size
     * Parse the body as HTML and return the following
       * If og:title exists, assoc it
       * If a <title> tag exists, assoc it
   * Otherwise, do nothing

   Protections:
   * Socket timeout
   * Connection timeout
   * Max redirect amount
   * Max content size read from stream
   * Graceful parsing of partial and junk HTML
   * Graceful handling of junk in parsed headers"
  [url]
  (let [http-opts {:throw-exceptions false
                   :ignore-unknown-host? true
                   :max-redirects 5
                   :redirect-strategy :graceful
                   :socket-timeout 2000
                   :connection-timeout 2000}
        domain (util/url->domain url)
        ; TODO: Can we remove the HEAD altogether and just use a GET?
        head (http/head url http-opts)
        head-status (get head :status 404)
        html? (some #(string/includes? (get-in head [:headers "content-type"] "") %)
                    ["text/html" "application/xhtml+xml"])
        redirects (:trace-redirects head)]
    (cond
      (not= 200 head-status)
      nil

      (not html?)
      (let [last-domain (util/url->domain (or (last redirects) url))]
        (merge {:status head-status
                :content-type (get-in head [:headers "content-type"])}
               (when-not (= domain last-domain)
                 {:domain last-domain})
               (let [length (util/parse-int (get-in head [:headers "content-length"]) 0)]
                 (when-not (zero? length)
                   {:content-length length}))))

      :else
      (try
        (let [response (http/get url (assoc http-opts :as :stream))
              ; TODO: We could check the status and content-type again here.
              parsed-html (with-open [body-stream (:body response)]
                            (-> body-stream
                                (util/read-stream-str! max-content-size)
                                reaver/parse))
              title (or (-> (reaver/select parsed-html "head meta[property=og:title]")
                            reaver/edn
                            :attrs
                            :content)
                      (-> (reaver/select parsed-html "head title")
                          reaver/text))]
          (when (some? title)
            {:status (:status response)
             ; Some sites have multiple titles.
             :title (if (seq? title)
                      (first title)
                      title)}))
        (catch Exception _
          nil)))))
(def fetch-url! (memo/ttl fetch-url!* {} :ttl/threshold (* 60 1000))); ms

(defn url-info->message [info]
  (-> (cond-> ""
        (-> info :domain some?)
        (str "\u0002Domain\u000F: " (:domain info) " ")

        (-> info :content-type some?)
        (str "\u0002Type\u000F: " (:content-type info) " ")

        (-> info :content-length some?)
        (str "\u0002Size\u000F: " (-> info :content-length util/size->human-readable) " ")

        (-> info :title some?)
        (str "\u0002Title\u000F: " (:title info) " "))
      ; Strip out new lines, tabs, indentation, etc.
      (string/replace #"\s+" " ")
      (util/truncate util/max-message-length)))

(defn process-message! [op]
  (when (and (state/feature-enabled? :title-fetch)
             (= (:channel op) (:primary-channel @state/global-config)))
    (let [urls (->> (:parts op)
                    (filter (fn [s]
                              (re-matches #"https?://\S+" s))))
          infos (mapv #(future (fetch-url! %)) urls)]
      (doseq [info infos]
        (when-some [info @info]
          (.respondWith (:event op) (url-info->message info)))))))
