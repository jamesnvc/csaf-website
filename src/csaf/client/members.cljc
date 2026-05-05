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
  [:div.member-manage {:tw "flex flex-col gap-3"}
   [:a {:href "/admin/users/manage"} "Back to Manage Members"]
   [:h3 "Manage Member"]

   (when message
     [:div {:tw ["bg-green-100 rounded text-green-700 p-2 m-4"
                "border border-1px border-solid border-green-700"] }
      message])

   [:form.member-info
    {:method "post"
     :action (str "/admin/users/manage/" (:members/id member))
     :tw "flex flex-col gap-4"}
    [:fieldset {:tw "text-xl"}
     [:label [:span "First Name"]
      [:input {:value (:members/first-name member)
               :name "first_name"
               :required true}]]
     " "
     [:label [:span "Last Name"]
      [:input {:value (:members/last-name member)
               :name "last_name"
               :required true}]]]

    [:p "Login: " (:members/login member)]

    [:fieldset
     [:label [:span "Birth Date"]
      [:input {:type "date"
               :value (:members/birth-date member)
               :name "birth_date"
               :placeholder "1980-01-01"}]]]

    [:fieldset
     [:label [:span "Email"]
      [:input {:type "email"
               :value (or (:members/email member) "")
               :name "email"
               :placeholder "user@example.com"}]]]

    [:fieldset.location
     [:label [:span "Country"]
      [:input {:value (:members/country member)
               :placeholder "Canada"
               :name "country"
               :required true}]]
     [:br]
     [:label [:span "City"]
      [:input {:value (:members/city member)
               :placeholder "Ottawa"
               :name "city"}]]
     ", "
     [:label [:span "Province"]
      [:input {:value (:members/province member)
               :placeholder "Ontario"
               :name "province"}]]
     [:br]
     [:label [:span "Postal Code"]
      [:input {:value (:members/postal-code member)
               :placeholder "M1M 1M1"
               :name "postal_code"}]]]

    [:fieldset.stats
     (for [prop [:members/height :members/weight :members/tartan]]
       [:label [:span (string/capitalize (name prop))]
        [:input {:value (get member prop)
                 :name (name prop)
                 :placeholder "..."}]])]

    [:fieldset
     [:label.full [:span "Bio"]
      [:textarea {:value (:members/biography member)
                  :name "biography"
                  :placeholder "Lifter biography goes here"
                  :tw "w-100%"}]]]

    [:button "Save"]]

   [:form {:method "post"
           :action (str "/admin/users/manage/" (:members/id member) "/reset-password")}
    [:input {:type "submit" :value "Reset Password"
             :tw styles/a-tw}]]])
