package sam.pkg;

import static sam.fx.helpers.FxClassHelper.addClass;
import static sam.myutils.Checker.isEmpty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.NamedArg;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import sam.fx.helpers.FxConstants;
import sam.fx.helpers.FxHBox;
import sam.fx.helpers.FxText;
import sam.fx.helpers.FxUtils;

public abstract class ListView2<T> extends BorderPane {
	private final Logger LOGGER = LoggerFactory.getLogger(getClass());

	protected final ListView<T> list = new ListView<>();
	private ObservableList<T> items = list.getItems();
	private final TextField search = new TextField();
	private List<T> all;

	public ListView2(@NamedArg("title") String title) {
		addClass(this, "list-view2");

		setTop(title(title));
		setCenter(list);

		search.setDisable(true);
		search.textProperty().addListener(this::search);
		Platform.runLater(() -> setBottom(bottom()));
	}

	private Node bottom() {
		Node ret;

		Node[] btns = buttons();
		if(isEmpty(btns)) 
			ret = search;
		 else 
			ret = new VBox(2, FxHBox.buttonBox(btns), search);

		BorderPane.setMargin(ret, FxConstants.INSETS_5);
		return ret;
	}

	private Node title(String title) {
		Text t = FxText.text(title, "title");
		StringBuilder sb = new StringBuilder(title).append(" (");
		int n = sb.length();

		ObservableList<T> list = this.list.getItems();  

		list.addListener(FxUtils.invalidationListener(i -> {
			sb.setLength(n);
			sb.append(list.size())
			.append(")");

			t.setText(sb.toString());
		}));

		BorderPane.setAlignment(t, Pos.CENTER_RIGHT);
		BorderPane.setMargin(t, FxConstants.INSETS_5);

		return t;
	}

	protected abstract Node[] buttons();
	protected abstract String searchValue(T t);

	public void clear() {
		items.clear();
	}
	public void setAll(List<T> col) {
		items.setAll(col);
	}

	public ObservableList<T> getItems() {
		return list.getItems();
	}
	
	public ReadOnlyObjectProperty<T> selectedItemProperty() {
		return list.getSelectionModel().selectedItemProperty();
	}
	public T selected() {
		return list.getSelectionModel().getSelectedItem();
	}
	
	private boolean selfChange = false;
	private String oldSearch;
	private List<T> searchSink; 
	
	private void search(Object ignore) {
		if(selfChange)
			return;
		
		list.getSelectionModel().clearSelection();
		String s = search.getText() == null ? null : search.getText().toLowerCase();
		
		if(isEmpty(s)) {
			items.setAll(all);
			LOGGER.debug("Search: items.setAll({}): \"{}\"", all.size(), s);
		} else if(oldSearch != null && s.contains(oldSearch)) {
			int size = items.size();
			items.removeIf(t -> !searchValue(t).contains(s));
			LOGGER.debug("Search: items.removeIf(...) {} -> {}: \"{}\"", size, items.size(), s);
		} else {
			if(searchSink == null)
				searchSink = new ArrayList<>();
			
			String z = s;
			all.forEach(t -> {
				if(searchValue(t).contains(z))
					searchSink.add(t);
			});
			
			int size = items.size();
			items.setAll(searchSink);
			searchSink.clear();
			
			LOGGER.debug("Search: items.addIf(...) {} -> {}: \"{}\"", size, items.size(), s);
		}
		oldSearch = s;
	}

	public void setItems(List<T> data) {
		selfChange = true;
		
		data = data == null ? Collections.emptyList() : data;

		list.getSelectionModel().clearSelection();
		items.setAll(data);
		all = data;
		search.setDisable(isEmpty(all));
		search.clear();
		
		selfChange = false;
	}
}
