package sam.pkg;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableObjectValue;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import sam.fx.helpers.FxButton;
import sam.fx.helpers.FxCell;
import sam.pkg.jsonfile.api.JsonFile;
import sam.pkg.jsonfile.api.Template;

public class TemplateList extends ListView2<Template> {
	private final Button addBtn = FxButton.button("ADD", this::addAction);
	private final Button removeBtn = FxButton.button("REMOVE", this::removeAction);

	public TemplateList() {
		super("entries");
		
		removeBtn.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
		list.setCellFactory(FxCell.listCell(t -> t.id()));
	}
	
	@Override
	protected Node[] buttons() {
		return new Node[]{addBtn, removeBtn};
	}

	private void removeAction(Event e) {
		/** FIXME
		 * ArrayList<Template> keys = new ArrayList<>(smTemplates.getSelectedItems());
		if(keys.isEmpty()) return;
		if(this.keys != null)
			this.keys.removeAll(keys);
		smTemplates.clearSelection();
		keys.forEach(Template::remove);
		 */
	}
	@Override
	protected String searchValue(Template t) {
		return t.id().toLowerCase();
	}

	@FXML
	private void addAction(Event e) {

		/** FIXME 
		 * private Adder adder;
		private List<Template> keys;
		if(adder == null)
			adder = MyUtilsException.noError(() -> new Adder(stage));		
		 * 
		 * keys = keys != null ? keys : jsonFiles().stream().flatMap(f -> f.getTemplates()).collect(Collectors.toList());
		JsonFile f = json();
		if(f.hasError()) 
			smJson.clearSelection();

		jsonFiles().removeIf(j -> {
			if(j.hasError()) {
				FxAlert.showErrorDialog(j.source, "failed loading file", j.error());
				return true;
			}
			return false;
		});

		if(f.hasError())
			return;

		String id = adder.newId(keys);
		if(id == null) {
			FxPopupShop.showHidePopup("cancelled", 1500);
			return;
		}
		Template key = f.add(id, template());
		keys.add(key);
		listEntries.getItems().add(key);
		smTemplates.clearAndSelect(listEntries.getItems().size() - 1);
		 * 		keys = keys != null ? keys : jsonFiles().stream().flatMap(f -> f.getTemplates()).collect(Collectors.toList());
		JsonFile f = json();
		if(f.hasError()) 
			smJson.clearSelection();

		jsonFiles().removeIf(j -> {
			if(j.hasError()) {
				FxAlert.showErrorDialog(j.source, "failed loading file", j.error());
				return true;
			}
			return false;
		});

		if(f.hasError())
			return;

		String id = adder.newId(keys);
		if(id == null) {
			FxPopupShop.showHidePopup("cancelled", 1500);
			return;
		}
		Template key = f.add(id, template());
		keys.add(key);
		listEntries.getItems().add(key);
		smTemplates.clearAndSelect(listEntries.getItems().size() - 1);
		 */

	}

}
