(ns irc.libera.chat.bayaz.operation.public.core-test
  (:require [clojure.test :as t]
            [clj-http.client :as http]
            [irc.libera.chat.bayaz.operation.public.core :as operation.public.core])
  (:import [java.io ByteArrayInputStream]
           [java.util.concurrent ThreadLocalRandom]))

(defn str->stream [^String s]
  (-> s .getBytes ByteArrayInputStream.))

(t/deftest fetch-url
  (let [url "http://jeaye.com/"]
    (t/testing "head"
      (t/testing "nil"
        (with-redefs [http/head (fn [_url _opts]
                                  nil)]
          (t/is (= nil (operation.public.core/fetch-url!* url)))))

      (t/testing "non-200"
        (with-redefs [http/head (fn [_url _opts]
                                  {:status 400})]
          (t/is (= nil (operation.public.core/fetch-url!* url))))))

    (t/testing "domain resolution"
      (t/testing "no redirect; same domain"
        (with-redefs [http/head (fn [_url _opts]
                                  {:status 200
                                   :headers {"content-type" "text/json"}
                                   :trace-redirects []})]
          (t/is (= {:status 200
                    :content-type "text/json"}
                   (operation.public.core/fetch-url!* url)))))
      (t/testing "redirect; same domain"
        (with-redefs [http/head (fn [url _opts]
                                  {:status 200
                                   :headers {"content-type" "text/json"}
                                   :trace-redirects [url]})]
          (t/is (= {:status 200
                    :content-type "text/json"}
                   (operation.public.core/fetch-url!* url)))))
      (t/testing "redirect; different domain"
        (with-redefs [http/head (fn [_url _opts]
                                  {:status 200
                                   :headers {"content-type" "text/json"}
                                   :trace-redirects ["http://foo.test"]})]
          (t/is (= {:status 200
                    :content-type "text/json"
                    :domain "foo.test"}
                   (operation.public.core/fetch-url!* url))))))

    (t/testing "html title resolution"
      (t/testing "<title>"
        (with-redefs [http/head (fn [_url _opts]
                                  {:status 200
                                   :headers {"content-type" "text/html"}})
                      http/get (fn [_url _opts]
                                 {:status 200
                                  :headers {"content-type" "text/html"}
                                  :body (str->stream "<html><head><title>HTML Title</title>")})]
          (t/is (= {:status 200
                    :title "HTML Title"}
                   (operation.public.core/fetch-url!* url)))))
      (t/testing "og:title"
        (with-redefs [operation.public.core/max-content-size 100
                      http/head (fn [_url _opts]
                                  {:status 200
                                   :headers {"content-type" "text/html"}})
                      http/get (fn [_url _opts]
                                 {:status 200
                                  :headers {"content-type" "text/html"}
                                  :body (str->stream "<html><head><meta data-rh=\"true\" content=\"OG Title\" property=\"og:title\">")})]
          (t/is (= {:status 200
                    :title "OG Title"}
                   (operation.public.core/fetch-url!* url)))))

      (t/testing "application/xhtml+xml <title>"
        (with-redefs [http/head (fn [_url _opts]
                                  {:status 200
                                   :headers {"content-type" "application/xhtml+xml"}})
                      http/get (fn [_url _opts]
                                 {:status 200
                                  :headers {"content-type" "text/html"}
                                  :body (str->stream "<html><head><title>HTML Title</title>")})]
          (t/is (= {:status 200
                    :title "HTML Title"}
                   (operation.public.core/fetch-url!* url))))))

    (t/testing "invalid html"
      (t/testing "empty string"
        (with-redefs [operation.public.core/max-content-size 100
                      http/head (fn [_url _opts]
                                  {:status 200
                                   :headers {"content-type" "text/html"}})
                      http/get (fn [_url _opts]
                                 {:status 200
                                  :headers {"content-type" "text/html"}
                                  :body (str->stream "")})]
          (t/is (= nil (operation.public.core/fetch-url!* url)))))
      (t/testing "random string"
        (with-redefs [operation.public.core/max-content-size 100
                      http/head (fn [_url _opts]
                                  {:status 200
                                   :headers {"content-type" "text/html"}})
                      http/get (fn [_url _opts]
                                 (let [random (ThreadLocalRandom/current)
                                       rand-bytes (byte-array 256)
                                       _ (.nextBytes random rand-bytes)
                                       body (String. rand-bytes)]
                                   {:status 200
                                    :headers {"content-type" "text/html"}
                                    :body (str->stream body)}))]
          (t/is (= nil (operation.public.core/fetch-url!* url))))))))
