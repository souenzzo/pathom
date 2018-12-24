(ns com.wsscode.pathom.diplomat.http.node-https
  (:require
    [com.wsscode.common.async-cljs :refer [go-promise let-chan <!p go-catch <? <?maybe]]
    [goog.object :as gobj]
    [com.wsscode.pathom.diplomat.http :as http]
    [clojure.core.async :as async]
    [com.wsscode.pathom.core :as p]
    ["process" :as node.process]
    ["buffer" :as node.buffer]
    ["https" :as node.https]
    ["url" :as node.url]
    [clojure.string :as string]
    [com.wsscode.pathom.trace :as pt]))

(defn- buffer
  ([] (buffer #js []))
  ([& args] (apply node.buffer/Buffer.from args)))

(defn- buffer-concat
  [buffs]
  (.concat node.buffer/Buffer (into-array buffs)))

(defn- buffer-count
  [buff]
  (if buff
    (.-length buff)
    0))

(def *user-agent*
  "kind of 'node/vX.Y.Z' by default"
  (let [p js/process]
    (str (gobj/get p "title") "/" (gobj/get p "version"))))

(defn build-headers
  [{::http/keys [headers
                 accept
                 content-type]} content-length]
  (let [base-headers (cond-> {:content-length content-length
                              :user-agent     *user-agent*}
                             content-type (assoc :content-type (http/encode-type->header content-type))
                             accept (assoc :accept (http/encode-type->header accept)))]
    (merge base-headers headers)))

(defn build-body [{::http/keys [form-params]}]
  (buffer (js/JSON.stringify (clj->js form-params)) "utf-8"))

(defn body+headers
  [{::http/keys [url]
    :as         request}]
  (let [url' (node.url/parse url)
        method (string/upper-case (http/request-method request))
        body (build-body request)
        content-length (buffer-count body)
        headers (build-headers request content-length)]
    {:label   (str method " " url)
     :url     url
     :method  method
     :options #js {:protocol (.-protocol url')
                   :hostname (.-hostname url')
                   :path     (.-path url')
                   :port     (.-port url')
                   :auth     (.-auth url')
                   :method   method
                   :headers  (clj->js headers)}
     :body    body}))


(defn request-async
  [{::http/keys [debug?]
    :as         request}]
  (let [response-chan (async/promise-chan)
        status-chan (async/promise-chan)
        headers-chan (async/promise-chan)
        body-chan (async/promise-chan)
        on-error (fn [error] (async/put! response-chan error))
        on-response (fn [response]
                      (let [body (atom (buffer))]
                        (async/put! headers-chan (js->clj (gobj/get response "headers")
                                                          :keywordize-keys true))
                        (async/put! status-chan (gobj/get response "statusCode"))
                        (doto response
                          (.on "data" (fn [new-data]
                                        (swap! body #(buffer-concat [% new-data]))))
                          (.on "end" (fn []
                                       (try
                                         (async/put! body-chan (js->clj (js/JSON.parse @body) :keywordize-keys true))
                                         (catch :default e
                                           (on-error e))))))))
        {:keys [options body label url method]} (body+headers request)
        tid (when debug?
              (pt/trace-enter request {::pt/event ::request-async
                                       ::pt/label label
                                       ::url      url
                                       ::method   method}))
        req (doto (node.https/request options on-response)
              (.on "error" on-error))]
    (.write req body)
    (.end req)
    (async/go
      (let [status (async/<! status-chan)]
        (async/>! response-chan {::http/status  status
                                 ::http/headers (async/<! headers-chan)
                                 ::http/body    (async/<! body-chan)})
        (when tid
          (pt/trace-leave request tid
                          (cond-> {} (not (< 199 status 300)) (assoc ::p/error {:status status}))))))
    response-chan))
