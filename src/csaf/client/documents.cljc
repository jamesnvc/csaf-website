(ns csaf.client.documents
  (:require
   [clojure.string :as string]
   [csaf.client.styles :as styles]))

(defn documents-view
  [existing-docs]
  [:div#documents
   [:div
    [:h1 "Documents"]
    [:form {:action "/admin/documents" :method "POST" :enctype "multipart/form-data"}
     [:label {:tw "block"} "Add File"
      [:input {:type "file" :name "document"}]]
     [:button "Upload"]]
    [:div#docs-list {:tw "flex flex-col md:grid md:grid-cols-3"}
     (doall
       (for [document existing-docs]
         [:a {:href (str "/documents/" document)} document]))]]])
