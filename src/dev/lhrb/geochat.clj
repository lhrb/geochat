(ns dev.lhrb.geochat
  (:require
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.ring-middlewares :as middlewares]
   [io.pedestal.log :as log]
   [ring.middleware.session.cookie :as cookie]
   [hiccup2.core :as hiccup]
   [hiccup.page :refer [doctype]]
   [io.pedestal.http.sse :as sse]
   [clojure.pprint :as pprint]
   [clojure.core.async :as async]
   [malli.core :as m]
   [malli.transform :as mt]
   [malli.error :as me])
  (:import
   (ch.hsr.geohash GeoHash))
  (:gen-class))

;; ----------------------------------------  pub and sub  ----------------------------------------

(def pub-channel (async/chan 1))
(def publisher (async/pub pub-channel :tag))
(def print-channel (async/chan 1))

(defn run-print-channel
  []
  (async/go-loop []
    (when-let [value (async/<! print-channel)]
      (println value)
      (recur))))

(defn close-channels
  []
  (async/close! pub-channel)
  (async/close! print-channel))

(defn subscribe
  [publisher subscriber tags]
  (let [channel (async/chan 1)]
    (doseq [tag tags]
      (async/sub publisher tag channel))
    (async/go-loop []
      (when-let [value (async/<! channel)]
        (async/>! subscriber (:msg value))
        (recur)))))

(defn send-with-tags
  [channel msg]
  (doseq [tag (:tags msg)]
    (println "sending... " tag "message " msg)
    (async/>!! channel {:tag tag
                        :msg (:msg msg)})))

;; ------------------------------------------  helpers  ------------------------------------------

(defn html [hiccup]
  (str (hiccup/html {:mode :html} (doctype :html5) hiccup)))

(defn ping [req]
  {:status 200
   :body (pprint/pprint req)})

(def head
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:script {:src "https://unpkg.com/htmx.org@1.8.0"
             :integrity "sha384-cZuAZ+ZbwkNRnrKi05G/fjBX+azI9DNOkNYysZ0I/X5ZFgsmMiBXgDZof30F5ofc"
             :crossorigin "anonymous"}]
   [:script {:src "https://unpkg.com/htmx.org@1.8.0/dist/ext/sse.js" :defer true}]
   [:script {:src "https://unpkg.com/alpinejs@3.x.x/dist/cdn.min.js" :defer true}]])

(defn geohash
  "geohash for an area of roughly 5 square km"
  [^Double latitude ^Double longitude]
  (.toBase32 (GeoHash/withCharacterPrecision latitude longitude 5)))

;; -------------------------------------------  pages  -------------------------------------------

(defn landing-page [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   (html
    [:html
     head
      [:body
       [:h1 "Who are you?"]
       [:div {:x-data "{ locationReceived : false,
                         longitude : 'longitude',
                         latitude : 'latitude',
                         accuracy : 'accuracy'}"
              :x-init "navigator.geolocation.getCurrentPosition((position) => {
                            console.log(position);
                            longitude = position.coords.longitude;
                            latitude = position.coords.latitude;
                            accuracy = position.coords.accuracy;
                            locationReceived = true;
                       })"}
        [:form {:action "/login" :method "post"}
         [:input {:type "text" :name "name" :placeholder "Enter your name"
                  :required true :maxlength "10"}] [:br]
         [:input {:type "text" :name "longitude" :x-bind:value "longitude"}] [:br]
         [:input {:type "text" :name "latitude" :x-bind:value "latitude"}] [:br]
         [:input {:type "text" :name "accuracy" :x-bind:value "accuracy"}] [:br]
         [:input {:type "submit" :value "Submit" :x-bind:disabled "!locationReceived"}]]]]])})

(def login-schema
  (m/schema
   [:map
    [:name {:min 2 :max 3} string?]
    [:longitude double?]
    [:latitude double?]
    [:accuracy [:and double? [:< 200]]]]))

(def login-parser
  {:name ::login-parser
   :enter
   (fn [ctx]
     (let [form-params (get-in ctx [:request :form-params])
           decoded (m/decode [:map
                              [:name string?]
                              [:longitude double?]
                              [:latitude double?]
                              [:accuracy double?]]
                             form-params
                             mt/string-transformer)]
       (if (m/validate login-schema decoded)
         (assoc-in ctx [:request :parsed] decoded)
         (assoc ctx :response {:status 400
                               :body (str
                                      (me/humanize
                                       (m/explain
                                        login-schema
                                        decoded)))}))))})

(def t
  {:name ::tt
   :enter (fn [ctx]
            (def c ctx)
            ctx)
   :leave (fn [ctx]
           (def c2 ctx)
           ctx)})

;; (get-in c [:request :form-params])
;; ((:enter login-parser) c)

(defn login
  [req]
  (let [params (:form-params req)
        {:keys [name latitude longitude]} params
        geohash' (geohash (parse-double latitude) (parse-double longitude))]
    (log/debug "CREATE" (str "new session " {::name name ::topic geohash'}))
   {:status 303
    :headers {"Location" "/chat"}
    :session {::name name ::topic geohash'}}))

(def submit-form
  [:form {:hx-post "/chat/submit"}
   [:input {:name "message" :type "text"}]
   [:button {:type "submit"} "Submit"]])

(defn chat
  [request]
  (let [name (get-in request [:session ::name])
        topic (get-in request [:session ::topic])]
   {:status 200
    :headers {"Content-Type" "text/html"}
    :body
    (html
     [:html
      head
      [:body
       [:h1 (str "Hi " name " everything from " topic)]
       [:div {:hx-ext "sse" :sse-connect "/chat/subscribe" :sse-swap "message"}
        "> there will be text"]
       submit-form]])}))

(defn send-message
  [request]
  (let [{:keys [session form-params]} request]
   (send-with-tags pub-channel
                   {:msg {:data (:message form-params)
                          :name "message"}
                    :tags [(keyword (::topic session))]})
   {:status 200
    :body
    (html submit-form)}))

(defn subscribe-see
  [event-ch ctx]
  (let [session (:session (:request ctx))
        tags [(keyword (::topic session))]]
    (subscribe publisher event-ch tags)))

;; -------------------------------------------  server  -------------------------------------------

(def common-interceptors [(body-params/body-params)
                          http/html-body
                          (middlewares/session {:store (cookie/cookie-store)})])

(def routes #{["/" :get (conj common-interceptors `landing-page)]
              ["/login" :post (conj common-interceptors `login-parser `t `login)]
              ["/chat" :get (conj common-interceptors `chat)]
              ["/chat/subscribe" :get (conj common-interceptors `(sse/start-event-stream subscribe-see))]
              ["/chat/submit" :post (conj common-interceptors `send-message)]})

;; create /etc/profile.d/keystore.sh
;; insert into file
;; export KEYSTORE=/path/to/key
;; export KEYSTORE_PASS=your-keystore-pass
(def env (System/getenv))

(def service {:env                     :prod
              ::http/routes            routes
              ::http/resource-path     "/public"
              ::http/type              :jetty
              ::http/host              "0.0.0.0"
              ::http/port              8080

              ;; Ehm yeah figure out how to configure this correctly
              ;;
              ;; all origins are allowed in dev mode
              ::http/allowed-origins {:creds true :allowed-origins (constantly true)}
               ;; Content Security Policy (CSP) is mostly turned off in dev mode
              ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}

              ::http/container-options {:h2c? true
                                        :h2?  false
                                        :ssl? true
                                        :ssl-port 8443
                                        :keystore (get env "KEYSTORE")
                                        :key-password (get env "KEYSTORE_PASS")
                                        :security-provider "Conscrypt"}})

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
;; (defonce runnable-service (http/create-server service))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [& args]
  (println "\nCreating your [DEV] http...")
  (-> service ;; start with production configuration
      (merge {:env :dev
              ;; do not block thread that starts web server
              ::http/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ::http/routes #(route/expand-routes (deref #'routes))
              ;; all origins are allowed in dev mode
              ::http/allowed-origins {:creds true :allowed-origins (constantly true)}
               ;; Content Security Policy (CSP) is mostly turned off in dev mode
              ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}})
      ;; Wire up interceptor chains
      http/default-interceptors
      http/dev-interceptors
      http/create-server
      http/start))


(defn greet
  "Callable entry point to the application."
  [data]
  (println (str "Hello, " (or (:name data) "World") "!")))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
    (println "\nCreating Server http")
  (-> service
      http/default-interceptors
      http/dev-interceptors
      http/create-server
      http/start))


(comment
  (def server (run-dev))
  (http/stop server)

  (run-print-channel)

  (subscribe publisher print-channel [:dogs])
  (subscribe publisher print-channel [:cats])
  (subscribe publisher print-channel [:cats :dogs] )

  (subscribe publisher print-channel [:a])

  (send-with-tags pub-channel {:msg "New Dog Story" :tags [:dogs]})
  (send-with-tags pub-channel {:msg "New Cat Story" :tags [:cats]})
  (send-with-tags pub-channel {:msg "New Pet Story" :tags [:cats :dogs]})

  (send-with-tags pub-channel {:msg "New Cat Story" :tags [:a]})
  (send-with-tags pub-channel
                   {:msg {:name "message"
                          :data "warum32"}
                    :tags [:a]})
  (close-channels)


  (def req {:form-params {:name "a"
                        :longitude "7.2162363"
                        :latitude "51.4818445"
                        :accuracy "5612.0965"
                        }})

  (let [form (:form-params req)
        {:keys [name longitude latitude accuracy]} form]
    (parse-double longitude))
  ,)
