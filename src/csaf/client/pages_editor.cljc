(ns csaf.client.pages-editor)

(defn pages-editor-view
  [pages]
  [:div
   [:h2 "Pages"]

   [:ul
    (doall
      (for [{:pages/keys [title]} pages]
        [:a {:href (str "/admin/pages/" title)} title]))]

   [:form {:method "post" :action "/admin/pages"}
    [:input {:type "text" :name "title" :required true :placeholder "title"
             :tw "border-black border-solid border-1px"}]
    [:input {:type "submit" :value "Create"}]]])

(defn page-editor-view
  [{:keys [title content]}]
  [:div
   [:h2 (str "Editing " title)]

   [:div#editor
    [:hiccup/raw-html content]]

   [:form#editor-save-form {:method "post" :action (str "/admin/pages/" title)}
    [:input {:type "hidden" :name "content" :value ""}]
    [:input {:type "submit" :value "Save"}]]

   [:script {:src "/js/quill-2.0.2.js"}]
   [:script "const quill = new Quill('#editor', {theme: 'snow'});
const form = document.getElementById('editor-save-form');
form.onsubmit = function(e) {
  e.preventDefault();
  form.elements['content'].value = quill.getSemanticHTML();
  form.submit();
};
"]])
