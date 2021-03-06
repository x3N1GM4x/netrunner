(ns netrunner.account
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put!] :as async]
            [clojure.string :as s]
            [goog.dom :as gdom]
            [netrunner.auth :refer [authenticated avatar] :as auth]
            [netrunner.appstate :refer [app-state]]
            [netrunner.ajax :refer [POST GET]]
            [netrunner.cardbrowser :refer [cards-channel]]))

(defn load-alt-arts []
  (go (let [cards (->> (<! (GET "/data/altarts"))
                       :json
                       (filter :versions)
                       (map #(update-in % [:versions] conj "default"))
                       (map #(assoc % :title (some (fn [c] (when (= (:code c) (:code %)) (:title c))) (:cards @app-state))))
                       (into {} (map (juxt :code identity))))]
        (swap! app-state assoc :alt-arts cards))))

(defn handle-post [event owner url ref]
  (.preventDefault event)
  (om/set-state! owner :flash-message "Updating profile...")
  (swap! app-state assoc-in [:options :sounds] (om/get-state owner :sounds))
  (swap! app-state assoc-in [:options :background] (om/get-state owner :background))
  (swap! app-state assoc-in [:options :show-alt-art] (om/get-state owner :show-alt-art))
  (swap! app-state assoc-in [:options :sounds-volume] (om/get-state owner :volume))
  (swap! app-state assoc-in [:options :blocked-users] (om/get-state owner :blocked-users))
  (.setItem js/localStorage "sounds" (om/get-state owner :sounds))
  (.setItem js/localStorage "sounds_volume" (om/get-state owner :volume))

  (let [params (:options @app-state)]
    (go (let [response (<! (POST url params :json))]
          (if (= (:status response) 200)
            (om/set-state! owner :flash-message "Profile updated - Please refresh your browser")
            (case (:status response)
              401 (om/set-state! owner :flash-message "Invalid login or password")
              421 (om/set-state! owner :flash-message "No account with that email address exists")
              :else (om/set-state! owner :flash-message "Profile updated - Please refresh your browser")))))))

(defn add-user-to-block-list
  [owner user]
  (let [blocked-user-node (om/get-node owner "block-user-input")
        blocked-user (.-value blocked-user-node)
        my-user-name (:username user)
        current-blocked-list (om/get-state owner :blocked-users)]
    (set! (.-value blocked-user-node) "")
    (when (and (not (s/blank? blocked-user))
               (not= my-user-name blocked-user)
               (= -1 (.indexOf current-blocked-list blocked-user)))
      (om/set-state! owner :blocked-users (conj current-blocked-list blocked-user)))))

(defn remove-user-from-block-list
  [evt owner]
  (let [currElt (.-currentTarget evt)
        next-sib (gdom/getNextElementSibling currElt)
        user-name (gdom/getTextContent next-sib)
        current-blocked-list (om/get-state owner :blocked-users)]
    (when user-name
      (om/set-state! owner :blocked-users (vec (remove #(= % user-name) current-blocked-list))))))

(defn account-view [user owner]
  (reify
    om/IInitState
    (init-state [this] {:flash-message ""})

    om/IWillMount
    (will-mount [this]
      (om/set-state! owner :background (get-in @app-state [:options :background]))
      (om/set-state! owner :sounds (get-in @app-state [:options :sounds]))
      (om/set-state! owner :show-alt-art (get-in @app-state [:options :show-alt-art]))
      (om/set-state! owner :volume (get-in @app-state [:options :sounds-volume]))
      (om/set-state! owner :blocked-users (sort (get-in @app-state [:options :blocked-users] []))))

    om/IRenderState
    (render-state [this state]
      (sab/html
        [:div.container
         [:div.account
          [:div.panel.blue-shade.content-page#profile-form {:ref "profile-form"}
           [:h2 "Settings"]
           [:form {:on-submit #(handle-post % owner "/update-profile" "profile-form")}
            [:section
             [:h3 "Avatar"]
             (om/build avatar user {:opts {:size 38}})
             [:a {:href "http://gravatar.com" :target "_blank"} "Change on gravatar.com"]]

            [:section
             [:h3 "Sounds"]
             [:div
              [:label [:input {:type "checkbox"
                               :value true
                               :checked (om/get-state owner :sounds)
                               :on-change #(om/set-state! owner :sounds (.. % -target -checked))}]
               "Enable sounds"]]
             [:div "Volume"
              [:input {:type "range"
                       :min 1 :max 100 :step 1
                       :on-change #(om/set-state! owner :volume (.. % -target -value))
                       :value (om/get-state owner :volume)
                       :disabled (not (om/get-state owner :sounds))}]]]

            [:section
             [:h3  "Game board background"]
             (for [option [{:name "The Root"        :ref "lobby-bg"}
                           {:name "Freelancer"      :ref "freelancer-bg"}
                           {:name "Mushin No Shin"  :ref "mushin-no-shin-bg"}
                           {:name "Traffic Jam"     :ref "traffic-jam-bg"}
                           {:name "Rumor Mill"      :ref "rumor-mill-bg"}
                           {:name "Find The Truth"  :ref "find-the-truth-bg"}
                           {:name "Push Your Luck"  :ref "push-your-luck-bg"}
                           {:name "Apex"            :ref "apex-bg"}
                           {:name "Monochrome"      :ref "monochrome-bg"}]]
               [:div.radio
                [:label [:input {:type "radio"
                                 :name "background"
                                 :value (:ref option)
                                 :on-change #(om/set-state! owner :background (.. % -target -value))
                                 :checked (= (om/get-state owner :background) (:ref option))}]
                 (:name option)]])]

            [:section
             [:h3 "Alt arts"]
             [:div
              [:label [:input {:type "checkbox"
                               :name "show-alt-art"
                               :checked (om/get-state owner :show-alt-art)
                               :on-change #(om/set-state! owner :show-alt-art (.. % -target -checked))}]
               "Show alternate card arts"]]]

            [:section
             [:h3 "Blocked users"]
             [:div
              [:input.search {:on-key-down (fn [e]
                                             (when (= e.keyCode 13)
                                               (do
                                                 (add-user-to-block-list owner user)
                                                 (.preventDefault e))))
                              :ref "block-user-input"
                              :type "text" :placeholder "User name"}]
              [:button.block-user-btn {:type "button"
                                       :name "block-user-button"
                                       :on-click #(add-user-to-block-list owner user)}
               "Block user"]]
             (for [bu (om/get-state owner :blocked-users)]
               [:div.line
                [:button.small.unblock-user {:type "button"
                                             :on-click #(remove-user-from-block-list % owner)} "X" ]
                [:span.blocked-user-name (str "  " bu)]])]

            [:p
             [:button "Update Profile"]
             [:span.flash-message (:flash-message state)]]]]]]))))


(defn account [{:keys [user]} owner]
  (om/component
   (when user
     (om/build account-view user))))

(om/root account app-state {:target (. js/document (getElementById "account"))})
