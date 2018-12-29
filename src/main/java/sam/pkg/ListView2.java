package sam.pkg;

import java.util.List;

import javafx.beans.NamedArg;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.IntegerBinding;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import sam.fx.helpers.FxConstants;

public class ListView2<T> extends BorderPane {
	private final ListView<T> list = new ListView<>();
	
	public ListView2(@NamedArg("title") String title) {
		Text titlet = new Text(title);
		setTop(titlet);
		setCenter(list);
		
		BorderPane.setMargin(titlet, FxConstants.INSETS_5);
	}
	
	public MultipleSelectionModel<T> selectionModel() {
		return list.getSelectionModel();
	}

	public IntegerBinding sizeProperty() {
		return Bindings.size(list.getItems());
	}
	public void clear() {
		list.getItems().clear();
	}
	public void setAll(List<T> col) {
		list.getItems().setAll(col);
	}

	public ObservableList<T> getItems() {
		return list.getItems();
	}
}
