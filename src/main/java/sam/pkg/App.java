package sam.pkg;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import sam.fx.alert.FxAlert;
import sam.fx.helpers.FxCell;
import sam.fx.helpers.FxFxml;
import sam.fx.popup.FxPopupShop;
import sam.io.fileutils.FileOpenerNE;
import sam.myutils.MyUtilsException;
import sam.pkg.jsonfile.JsonFile;
import sam.pkg.jsonfile.JsonFile.Template;
import sam.pkg.jsonfile.JsonFiles;

// import sam.fx.helpers.FxConstants;

public class App extends Application {
	@FXML private SplitPane listSplit;
	@FXML private ListView2<JsonFile> listJson;
	@FXML private ListView2<Template> listEntries;
	@FXML private Button saveBtn;
	@FXML private Button addBtn;
	@FXML private Button openButton;
	@FXML private 	Label metaLabel;
	@FXML private Editor editor;

	private JsonFiles jsonFiles;

	MultipleSelectionModel<JsonFile> smJson;
	MultipleSelectionModel<Template> smTemplates;
	private static Stage stage;

	@Override
	public void start(Stage stage) throws Exception {
		App.stage = stage;
		FxFxml.load(this, stage, this);

		smJson = listJson.selectionModel();
		smJson.setSelectionMode(SelectionMode.SINGLE);
		smTemplates = listEntries.selectionModel();
		smTemplates.setSelectionMode(SelectionMode.MULTIPLE);
		listSplit.setDividerPositions(0.5);
		editor.init(smTemplates.selectedItemProperty(), saveBtn);

		listJson.setCellFactory(FxCell.listCell(JsonFile::toString));
		listEntries.setCellFactory(FxCell.listCell(Template::id));

		FxPopupShop.setParent(stage);
		FxAlert.setParent(stage);

		addBtn.disableProperty().bind(smJson.selectedItemProperty().isNull());
		smJson.selectedItemProperty().addListener((p, o, n) -> jsonChange(n));
		metaLabel.textProperty().bind(Bindings.concat(listEntries.sizeProperty(), " / ", Bindings.size(jsonFiles())));

		stage.setWidth(500);
		stage.setHeight(500);
		stage.show();

		jsonFiles = new JsonFiles();
		listJson.getItems().setAll(jsonFiles.getFiles());
	}

	private final List<Template> _temp_list = new ArrayList<>();

	private void jsonChange(JsonFile n) {
		smTemplates.clearSelection();
		if(n == null)
			listEntries.clear();
		else {
			_temp_list.clear();
			listEntries.setAll(n.getTemplates().collect(Collectors.toCollection(() -> _temp_list)));
			_temp_list.clear();

			if(n.hasError()) {
				FxAlert.showErrorDialog(n.getSourcePath(), "failed loading file", n.error());
				smJson.clearSelection();
				jsonFiles().remove(n);
				return;
			}
		}
	}
	@Override
	public void stop() throws Exception {
		jsonFiles.close();
		super.stop();
	}
	private ObservableList<JsonFile> jsonFiles() {
		return listJson.getItems();
	}
	@FXML
	private void openAction(Event e) {
		FileOpenerNE.openFileLocationInExplorer(json().getSourcePath().toFile());
	}
	@FXML
	private void removeAction(Event e) {
		ArrayList<Template> keys = new ArrayList<>(smTemplates.getSelectedItems());
		if(keys.isEmpty()) return;
		if(this.keys != null)
			this.keys.removeAll(keys);
		smTemplates.clearSelection();
		keys.forEach(Template::remove);
	}
	private Adder adder;
	private List<Template> keys;

	@FXML
	private void addAction(Event e) {
		if(adder == null)
			adder = MyUtilsException.noError(() -> new Adder(stage));

		keys = keys != null ? keys : jsonFiles().stream().flatMap(f -> f.getTemplates()).collect(Collectors.toList());
		JsonFile f = json();
		if(f.hasError()) 
			smJson.clearSelection();

		jsonFiles().removeIf(j -> {
			if(j.hasError()) {
				FxAlert.showErrorDialog(j.getSourcePath(), "failed loading file", j.error());
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
	}

	public JsonFile json() {
		return smJson.getSelectedItem();
	}
	public Template template() {
		return smTemplates.getSelectedItem();
	}
	public static Window stage() {
		return stage;
	}
}
