(ns netrunner.account
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put!] :as async]
            [netrunner.auth :refer [authenticated avatar] :as auth]
            [netrunner.appstate :refer [app-state]]
            [netrunner.ajax :refer [POST GET]]))

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
  (.setItem js/localStorage "sounds" (om/get-state owner :sounds))
  (.setItem js/localStorage "sounds_volume" (om/get-state owner :volume))

  (let [params (:options @app-state)]
    (go (let [response (<! (POST url params :json))]
          (if (= (:status response) 200)
            (om/set-state! owner :flash-message "Profile updated!")
            (case (:status response)
              401 (om/set-state! owner :flash-message "Invalid login or password")
              421 (om/set-state! owner :flash-message "No account with that email address exists")
              :else (om/set-state! owner :flash-message "Profile updated - Please refresh your browser")))))))

(defn account-view [user owner]
  (reify
    om/IInitState
    (init-state [this] {:flash-message ""})

    om/IWillMount
    (will-mount [this]
      (om/set-state! owner :background (get-in @app-state [:options :background]))
      (om/set-state! owner :sounds (get-in @app-state [:options :sounds]))
      (om/set-state! owner :show-alt-art (get-in @app-state [:options :show-alt-art]))
      (om/set-state! owner :volume (get-in @app-state [:options :sounds-volume])))

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
                           {:name "Apex"            :ref "apex-bg"}]]
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

            [:p
             [:button "Update Profile"]
             [:span.flash-message (:flash-message state)]]]]]]))))


(defn account [{:keys [user]} owner]
  (om/component
   (when user
     (om/build account-view user))))

(om/root account app-state {:target (. js/document (getElementById "account"))})
