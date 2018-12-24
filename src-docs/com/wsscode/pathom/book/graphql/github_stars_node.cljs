(ns com.wsscode.pathom.book.graphql.github-stars-node
  (:require
    [com.wsscode.common.async-cljs :refer [go-promise <?]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.diplomat.http.node-https :as node-https]
    [com.wsscode.pathom.connect.graphql :as pcg]
    [com.wsscode.pathom.core :as p]
    [goog.object :as gobj]
    [com.wsscode.pathom.diplomat.http :as p.http]
    [clojure.core.async :as async]))

(defonce indexes (atom {}))

(pc/defresolver repositories [_ _]
  {::pc/output [{:demo-repos [:github.user/login :github.repository/name]}]}
  {:demo-repos
   [{:github.user/login "wilkerlucio" :github.repository/name "pathom"}
    {:github.user/login "fulcrologic" :github.repository/name "fulcro"}
    {:github.user/login "fulcrologic" :github.repository/name "fulcro-inspect"}
    {:github.user/login "fulcrologic" :github.repository/name "fulcro-css"}
    {:github.user/login "fulcrologic" :github.repository/name "fulcro-spec"}
    {:github.user/login "thheller" :github.repository/name "shadow-cljs"}]})

(def github-gql
  {::pcg/url       (str "https://api.github.com/graphql?access_token=" (-> js/process
                                                                           (gobj/get "env")
                                                                           (gobj/get "GITHUB_TOKEN")))
   ::pcg/prefix    "github"
   ::pcg/ident-map {"user"       {"login" ["User" "login"]}
                    "repository" {"owner" ["User" "login"]
                                  "name"  ["Repository" "name"]}}
   ::p.http/driver node-https/request-async})

(def parser
  (p/parallel-parser
    {::p/env     {::p/reader               [p/map-reader
                                            pc/parallel-reader
                                            pc/open-ident-reader
                                            p/env-placeholder-reader]
                  ::p/placeholder-prefixes #{">"}
                  ::p.http/driver          node-https/request-async}
     ::p/mutate  pc/mutate-async
     ::p/plugins [(pc/connect-plugin {::pc/register repositories
                                      ::pc/indexes  indexes})
                  p/error-handler-plugin
                  p/request-cache-plugin
                  p/trace-plugin]}))

(defonce github-index-status
         (go-promise (<? (pcg/load-index github-gql indexes))))

(comment
  (async/go (prn (async/<! (parser {} [{[:github.user/login "wilkerlucio"] [:github.user/created-at]}])))))
