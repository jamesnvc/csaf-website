(ns csaf.client.members
  (:require
   [clojure.string :as string]
   [csaf.client.styles :as styles]))

(defn members-manage-view
  [{:keys [members]}]
  [:div {:tw "flex flex-col gap-3"}
   [:h2 "Members"]
   [:input {:id "members-search" :type "search" :placeholder "Filter Members" :tw "hidden"}]
   [:div#members-list {:tw "flex flex-col md:grid md:grid-cols-3"}
    (doall (for [member members]
            [:a {:href (str "/admin/users/manage/" (:members/id member)) :tw styles/a-tw}
             (:members/last-name member) ", " (:members/first-name member)]))]
   [:script
    [:hiccup/raw-html "
const searchField = document.getElementById('members-search');
searchField.classList.remove('hidden');
const membersTable = document.getElementById('members-list');
const doSearch = function () {
  if (searchField.value === '' || searchField.value === undefined) {
    membersTable.childNodes.forEach((n) => { n.classList.remove('hidden'); });
  } else {
    const input = searchField.value.toLowerCase();
    membersTable.childNodes.forEach((n) => {
     if (n.innerText.toLowerCase().indexOf(input) === -1) {
       n.classList.add('hidden');
     } else {
       n.classList.remove('hidden');
     }
    });
  }
};
let searchTypeTimeout = null;
searchField.addEventListener('input', function (evt) {
  clearTimeout(searchTypeTimeout);
  searchTypeTimeout = setTimeout(doSearch, 100);
});
doSearch();
"]]])

(defn member-manage-view
  [{:keys [member message]}]
  [:div {:tw "flex flex-col gap-3"}
   [:h3 "Manage Member"]

   (when message
     [:div {:tw ["bg-green-100 rounded text-green-700 p-2 m-4"
                "border border-1px border-solid border-green-700"] }
      message])

   [:h2 {:tw "text-xl"} (:members/first-name member) " " (:members/last-name member)]
   [:p "Login: " (:members/login member)]

   (when-let [email (:members/email member)]
     [:a {:tw styles/a-tw :href (str "mailto:" email)} email])

   [:div.location
    (let [locale (->> (keep member [:members/city :members/province])
                      (string/join ", "))]
      (when-not (string/blank? locale)
        [:div locale]))
    [:div (:members/country member)]
    (when-let [postal (:members/postal-code member)]
      [:div postal])]

   [:div.stats
    (let [dt-tw "after:content-[\"::\"]"]
      [:dl {:tw "grid gap-1" :style {:grid-template-columns "max-content max-content"}}
       (when-let [height (:members/height member)]
         [:<> [:dt {:tw dt-tw} "Height "]
          [:dd height]])

       (when-let [weight (:members/weight member)]
         [:<> [:dt {:tw dt-tw} "Weight "]
          [:dd weight]])

       (when-let [tartan (:members/tartan member)]
         [:<> [:dt {:tw dt-tw} "Tartan "]
          [:dd tartan]])])]

   (when-let [bio (:members/biography member)]
     [:div.bio [:hiccup/raw-html bio]])

   [:form {:method "post"
           :action (str "/admin/users/manage/" (:members/id member) "/reset-password")}
    [:input {:type "submit" :value "Reset Password"
             :tw styles/a-tw}]]])
