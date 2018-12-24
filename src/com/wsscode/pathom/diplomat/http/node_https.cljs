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
  [buffa buffb]
  (.concat node.buffer/Buffer #js[buffa buffb]))

(defn- buffer-count
  [buff]
  (.-length buff))

(defn build-headers
  [{::http/keys [headers
                 accept
                 content-type]} content-length user-agent]
  (merge headers
         (cond-> {:content-length content-length
                  :user-agent     user-agent}
                 content-type (assoc :content-type (http/encode-type->header content-type))
                 accept (assoc :accept (http/encode-type->header accept)))))

(defn build-args
  [headers {::http/keys [url] :as request}]
  (let [url' (node.url/parse url)
        method (string/upper-case (http/request-method request))]
    {:label   (str method " " url)
     :url     url
     :options #js {:protocol (.-protocol url')
                   :hostname (.-hostname url')
                   :path     (.-path url')
                   :port     (.-port url')
                   :auth     (.-auth url')
                   :method   method
                   :headers  (clj->js headers)}
     :method  method}))


(defn request-async-factory
  [{::keys [user-agent
            clj->body
            body->clj]}]
  (fn request-async
    [{::http/keys [debug?]
      :as         request}]
    (let [response-chan (async/promise-chan)
          status-chan (async/promise-chan)
          headers-chan (async/promise-chan)
          body-chan (async/promise-chan)
          body (clj->body request)
          content-length (buffer-count body)
          headers (build-headers request content-length user-agent)
          {:keys [options label url method]} (build-args headers request)
          tid (when debug?
                (pt/trace-enter request {::pt/event ::request-async
                                         ::pt/label label
                                         ::url      url
                                         ::method   method}))
          on-error (fn [ex]
                     (when tid
                       (pt/trace-leave request tid {::p/error (ex-message ex)}))
                     (async/put! response-chan ex))
          on-response (fn [response]
                        (let [body (atom (buffer))]
                          (async/put! headers-chan (js->clj (gobj/get response "headers")
                                                            :keywordize-keys true))
                          (async/put! status-chan (gobj/get response "statusCode"))
                          (doto response
                            (.on "data" #(swap! body buffer-concat %))
                            (.on "end" #(try
                                          (async/put! body-chan (body->clj request @body))
                                          (catch :default e
                                            (on-error e)))))))
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
      response-chan)))

(defn clj->body
  [{::http/keys [form-params]}]
  (buffer (js/JSON.stringify (clj->js form-params)) "utf-8"))

(defn body->clj
  [{::http/keys []} body]
  (js->clj (js/JSON.parse body) :keywordize-keys true))

(def request-async
  (request-async-factory
    {::user-agent (str (gobj/get js/process "title") "/" (gobj/get js/process "version"))
     ::clj->body  clj->body
     ::body->clj  body->clj}))
