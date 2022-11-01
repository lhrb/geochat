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
   [malli.error :as me]
   [clj-http.client :as client]
   [cheshire.core :as json]
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.java.io :as io]
   [clojure.edn :as edn])
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

(defn html
  "default html hiccup settings"
  [hiccup]
  (str (hiccup/html {:mode :html} (doctype :html5) hiccup)))

(defn ping
  "dbg request handler"
  [req]
  {:status 200
   :body (pprint/pprint req)})

(def head
  ;; default html header
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   ;[:link {:rel "stylesheet" :href "css/normalize.css"}]
   ;[:link {:rel "stylesheet" :href "css/skeleton.css"}]
   [:link {:rel "stylesheet" :href "css/output.css"}]
   [:script {:src "https://unpkg.com/htmx.org@1.8.0"
             :integrity "sha384-cZuAZ+ZbwkNRnrKi05G/fjBX+azI9DNOkNYysZ0I/X5ZFgsmMiBXgDZof30F5ofc"
             :crossorigin "anonymous"}]
   [:script {:src "https://unpkg.com/htmx.org@1.8.0/dist/ext/sse.js" :defer true}]
   [:script {:src "https://unpkg.com/alpinejs@3.x.x/dist/cdn.min.js" :defer true}]])

(defn geohash
  "geohash for an area of roughly 5 square km"
  [^Double latitude ^Double longitude]
  (.toBase32 (GeoHash/withCharacterPrecision latitude longitude 5)))

(def colors ["#ff0000" "#ff8700" "#ffd300" "#deff0a" "#a1ff0a"
             "#0aff99" "#0aefff" "#147df5" "#580aff" "#be0aff"])

(defn color-hash [s]
  (let [h (mod (hash s) 10)]
    (get colors h)))

;; -------------------------------------------  pages  -------------------------------------------

(defn landing-page[_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   (html
    [:html
     head
      [:body
       [:div {:x-data "{ longitude : 'longitude',
                         latitude : 'latitude',
                         accuracy : 'accuracy',
                         showAddressInput : false,
                         showGeolocation: false,
                         requestCount : 0,
                         status : 'Requesting geolocation ...',
                         requestLocation() {
                           navigator.geolocation.getCurrentPosition((position) => {
                             console.log(position);
                             if (position.coords.accuracy > 200 && this.requestCount < 1) {
                                this.status = 'Geolocation accuracy too low, trying again ...';
                                console.log(\"accuracy too low, request again\");
                                this.requestCount++;
                                this.requestLocation();
                             } else if (position.coords.accuracy > 200) {
                                this.status = 'Geolocation accuracy too low, enter your address to proceed.';
                                this.showAddressInput = true;
                             } else {
                                 this.status = 'Geolocation found.'
                                 this.showGeolocation = true;
                                 this.longitude = position.coords.longitude;
                                 this.latitude = position.coords.latitude;
                                 this.accuracy = position.coords.accuracy;
                             }
                           },
                           (error) => {
                             console.log(error);
                             this.status = 'Geolocation access blocked, enter your address to proceed.';
                             this.showAddressInput = true;
                           })}}"
               :x-init "requestLocation()"
              :class "min-h-screen bg-gray-100 flex flex-col justify-center py-12 px-6 lg:px-8"}
        [:div {:class "sm:mx-auto sm:w-full sm:max-w-md"}
         [:img {:class "mx-auto h-12 w-auto", :src "https://tailwindui.com/img/logos/workflow-mark-indigo-600.svg", :alt "Workflow"}]
         [:h2 {:class "mt-6 text-center text-3xl font-extrabold text-gray-900"} "Join the chat"]
         [:p {:class "mt-2 text-center text-sm text-gray-600 max-w" :x-text "status"}]]
        [:div {}
         [:div {:class "mt-8 sm:mx-auto sm:w-full sm:max-w-md"}
          [:div {:class "bg-white py-8 px-6 shadow rounded-lg sm:px-10"}
           [:form {:action "/login" :method "post" :class "mb-0 space-y-6"}
            [:div
             [:label {:for "name" :class "block text-sm font-medium text-gray-700"}
              "Displayname"]
             [:div {:class "mt-1"}
              [:input {:type "text" :name "name" :placeholder "Enter your name"
                       :required true :maxlength "10"
                       :class "w-full border-gray-300 rounded-lg shadow-sm"}]]]

            ;; geolocation accuracy is too low or access is denied. Show a form
            ;; where the user can input street, postcode city
            [:template {:x-if "showAddressInput"}
             [:div
              [:div
               [:label {:for "street" :class "block text-sm font-medium text-gray-700"}
                "Street and house number"]
               [:div {:class "mt-1"}
                [:input {:type "text" :name "street" :placeholder "Enter your street"
                         :required true
                         :class "w-full border-gray-300 rounded-lg shadow-sm"}]]]
              [:div {:class "mt-4 columns-2"}
               [:div
                [:label {:for "postcode" :class "block text-sm font-medium text-gray-700"}
                 "Postcode"]
                [:div {:class "mt-1"}
                 [:input {:type "text" :name "postcode" :placeholder "Enter your postcode"
                          :required true
                          :class "w-full border-gray-300 rounded-lg shadow-sm"}]]
                [:div
                 [:label {:for "city" :class "block text-sm font-medium text-gray-700"}
                  "City"]
                 [:div {:class "mt-1"}
                  [:input {:type "text" :name "city" :placeholder "Enter your city"
                           :required true
                           :class "w-full border-gray-300 rounded-lg shadow-sm"}]]]]]]]

            ;; Successfully read out the geolocation. Show lat lon and accuracy in readonly form fields
            [:template {:x-if "showGeolocation"}
             [:div
              [:div {:class "columns-3"}
               [:div
                [:label {:for "longitude" :class "block text-sm font-medium text-gray-700"}
                 "longitude"]
                [:div {:class "mt-1"}
                 [:input {:type "text" :name "longitude" :x-bind:value "longitude" :readonly true
                          :class "w-full border-gray-300 rounded-lg shadow-sm"}]]]
               [:div
                [:label {:for "longitude" :class "block text-sm font-medium text-gray-700"}
                 "latitude"]
                [:div {:class "mt-1"}
                 [:input {:type "text" :name "latitude" :x-bind:value "latitude" :readonly true
                          :class "w-full border-gray-300 rounded-lg shadow-sm"}]]]
               [:div
                [:label {:for "longitude" :class "block text-sm font-medium text-gray-700"}
                 "accuracy"]
                [:div {:class "mt-1"}
                 [:input {:type "text" :name "accuracy" :x-bind:value "accuracy" :readonly true
                          :class "w-full border-gray-300 rounded-lg shadow-sm"}]]]]]]

            [:button {:type "submit"
                      :class "w-full flex justify-center
                               py-2 px-4
                               border border-transparent
                               rounded-md shadow-sm text-sm
                               font-medium text-white
                               bg-indigo-600 hover:bg-indigo-700
                               focus:outline-none focus:ring-2
                               focus:ring-offset-2 focus:ring-indigo-500"} "Enter"]]]]]]]])})

(def login-schema
  (m/schema
   [:or
    [:map
     [:name {:min 2 :max 3} string?]
     [:longitude double?]
     [:latitude double?]
     [:accuracy [:and double? [:< 200]]]]
    [:map
     [:name {:min 2 :max 3} string?]
     [:street string?]
     [:city string?]
     [:postcode string?]]]))

(def login-parser
  ;; interceptor which validates the login request
  {:name ::login-parser
   :enter
   (fn [ctx]
     (let [form-params (get-in ctx [:request :form-params])
           decoded (m/decode [:map
                              [:name string?]
                              [:street string?]
                              [:city string?]
                              [:postcode string?]
                              [:longitude double?]
                              [:latitude double?]
                              [:accuracy double?]]
                             form-params
                             mt/string-transformer)]
       (if (m/validate login-schema decoded)
         (assoc-in ctx [:request :parsed] decoded)
         (assoc ctx :response {:status 400
                               :headers {"Content-Type" "text/plain"}
                               :body (str
                                      (me/humanize
                                       (m/explain
                                        login-schema
                                        decoded)))}))))})

(defn request-geocode
  "request geocode from geoapify api"
  [api-key text]
  (client/get "https://api.geoapify.com/v1/geocode/search"
              {:query-params {:text text :apiKey api-key :lang "en" :limit 1}}))

(defn lat-lon-from-address
  "parses the geocoding request and returns a map with the latitude
  and longitude"
  [api-key address]
  (let [geo-resp (-> (request-geocode api-key address)
                     (:body)
                     (json/parse-string true)
                     (get-in [:features 0 :properties]))]
    {:latitude (:lat geo-resp)
     :longitude (:lon geo-resp)}))

(defn geo-coding
  "returns an interceptor which will send an geocoding request
  if a address was attached to the context map."
  [req-lat-lon-from-address]
  {:name ::geocoding
   :enter
   (fn [ctx]
     (let [{:keys [street city postcode]} (get-in ctx [:request :parsed])]
       ;; probably can do better, fold this into the data when validating
       (if (not-any? nil? [street city postcode])
         ;; create address and log that we doing a request here
         (let [address (str street " " postcode " " city)]
          (log/info ::request (str "geolocation for " address))
          ;; this does not create the best user experience but will be fine for now.
          (let [lat-lon (req-lat-lon-from-address address)]
            (if (some nil? (vals lat-lon))
              (assoc ctx :response {:status 400
                                    :headers {"Content-Type" "text/plain"}
                                    :body "Could not geocode given address."})
              (update-in ctx [:request :parsed] #(merge % lat-lon)))))
        ctx)))})

(defn login
  "creates a session and redirects to the /chat page"
  [req]
  (let [params (:parsed req)
        {:keys [name latitude longitude]} params
        geohash' (geohash latitude longitude)]
    (log/info ::create (str "new session " {::name name
                                             ::topic geohash'}))
   {:status 303
    :headers {"Location" "/chat"}
    :session {::name name ::topic geohash' ::color (color-hash name)}}))

(def submit-form
  [:form {:class "flex gap-2" :hx-post "/chat/submit" :hx-swap "outerHTML"}
   [:input {:class "border-gray-300 rounded-lg shadow-sm grow shrink"
            :name "message" :type "text" :minlenght "1"}]
   [:button {:type "submit"
             :class "flex py-2 px-4
                     border border-transparent
                     rounded-md shadow-sm text-sm
                     font-medium text-white
                     bg-indigo-600 hover:bg-indigo-700
                     focus:outline-none focus:ring-2
                     focus:ring-offset-2 focus:ring-indigo-500"} "Submit"]])

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
       [:div {:class "flex max-h-screen min-h-screen flex-col bg-gray-100 py-4 px-2 lg:px-8"}
        [:div {:class "container mx-auto shrink grow overflow-y-scroll"}
         [:div {:hx-ext "sse" :sse-connect "/chat/subscribe" :hx-swap "beforeend" :sse-swap "message"}
          [:div {:class "bg-white shadow py-3 px-5 mb-1"}
           (str "Hi " name " you subscribed to geohash: " topic)]]]

        [:div {:class "container mx-auto px-1 py-2"}
         submit-form]]]])}))

(defn send-message
  [request]
  (let [{:keys [session form-params]} request]
    (when (not-empty (:message form-params))
     (send-with-tags pub-channel
                     {:msg {:data (html
                                   [:div {:class "bg-white shadow py-3 px-5"}
                                    [:span {:style (str "color:" (::color session))}
                                     (::name session)]
                                    [:p {:class "text-gray-700"}
                                     (:message form-params)]])
                            :name "message"}
                      :tags [(keyword (::topic session))]}))
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

(defn routes
  [req-lat-lon-from-address]
  #{["/" :get (conj common-interceptors `landing-page)]
    ["/login" :post (conj common-interceptors `login-parser (geo-coding req-lat-lon-from-address) `login)]
    ["/chat" :get (conj common-interceptors `chat)]
    ["/chat/subscribe" :get (conj common-interceptors `(sse/start-event-stream subscribe-see))]
    ["/chat/submit" :post (conj common-interceptors `send-message)]})

(defn service
  [{:keys [keystore keypass routes]}]
  {:env                     :prod
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
                             :keystore keystore
                             :key-password keypass
                             :security-provider "Conscrypt"}})

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
;; (defonce runnable-service (http/create-server service))

(defn run-dev
  "The entry-point for 'lein run-dev'"
  [args]
  (println "\nCreating your [DEV] http...")
  (-> (service args) ;; start with production configuration
      (merge {:env :dev
              ;; do not block thread that starts web server
              ::http/join? false
              ;; Routes can be a function that resolve routes,
              ;;  we can use this to set the routes to be reloadable
              ::http/routes #(route/expand-routes
                              (routes (partial
                                       lat-lon-from-address
                                       (:geocoding-api-key args))))
              ;; all origins are allowed in dev mode
              ::http/allowed-origins {:creds true :allowed-origins (constantly true)}
               ;; Content Security Policy (CSP) is mostly turned off in dev mode
              ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}})
      ;; Wire up interceptor chains
      http/default-interceptors
      http/dev-interceptors
      http/create-server
      http/start))

(defn attach-routes
  [{:keys [geocoding-api-key] :as m}]
  (assoc m :routes (routes (partial lat-lon-from-address geocoding-api-key))))

(def cli-options
  [["-c" "--config PATH" "Path to the config file. Should point to an .edn file and
 contain :keystore, :keypass :geocoding-api-key"
    :validate [#(.exists (io/file %)) ""]]])

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [opts (parse-opts args cli-options)]
    (if-let [error (:errors opts)]
      (println error)
      (let [config-file (get-in opts [:options :config])
            config (-> (slurp config-file)
                       (edn/read-string)
                       (attach-routes))]
        (log/info ::config config)
        (println "\nCreating Server http")
        (-> (service config)
            http/default-interceptors
            http/dev-interceptors
            http/create-server
            http/start)))))

(comment
  (def config-file ".config.edn")
  (def server (run-dev (->
                        (slurp config-file)
                        (edn/read-string)
                        (attach-routes))))
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

  (log/info "dGB" "hallo file1?")

  ,)
