package sam.pkg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
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
import sam.fx.helpers.FxFxml;
import sam.fx.popup.FxPopupShop;
import sam.io.fileutils.FileOpenerNE;
import sam.logging.MyLoggerFactory;
import sam.myutils.MyUtilsException;
import sam.myutils.System2;
import sam.pkg.jsonfile.JsonFile;
import sam.pkg.jsonfile.JsonFile.Template;
import sam.pkg.jsonfile.JsonFiles;

// import sam.fx.helpers.FxConstants;

public class Main extends Application {
	private static final Logger LOGGER = MyLoggerFactory.logger(Main.class);
	
	public final static Path SNIPPET_DIR = Paths.get(System2.lookup("snippets_dir"));

	public static void main(String[] args) {
		if(Files.notExists(SNIPPET_DIR)) {
			System.out.println("SNIPPET_DIR not found: "+SNIPPET_DIR);
			System.exit(0);
		}

		launch(args);
	}

	@FXML private SplitPane listSplit;
	@FXML private ListView2<JsonFile> listJson;
	@FXML private ListView2<Template> listEntries;
	@FXML private Button saveBtn;
	@FXML private Button addBtn;
	@FXML private Button openButton;
	@FXML private 	Label metaLabel;
	@FXML private Editor editor;
	
	private JsonFiles jsonFiles;

	public static final Path SELF_DIR = Paths.get(System2.lookup("SELF_DIR"));
	public static final Path CACHE_DIR = SELF_DIR.resolve("cache");
	
	public static final int snippet_dir_count = SNIPPET_DIR.getNameCount();
	public static final int self_dir_count = SELF_DIR.getNameCount(); 

	MultipleSelectionModel<JsonFile> smJson;
	MultipleSelectionModel<Template> smTemplates;
	private static Stage stage;

	@Override
	public void start(Stage stage) throws Exception {
		Main.stage = stage;
		FxFxml.load(this, stage, this);
		
		LOGGER.config(() -> "SNIPPET_DIR: "+SNIPPET_DIR);
		LOGGER.config(() -> "SELF_DIR: "+SELF_DIR);

		smJson = listJson.selectionModel();
		smJson.setSelectionMode(SelectionMode.SINGLE);
		smTemplates = listEntries.selectionModel();
		smTemplates.setSelectionMode(SelectionMode.MULTIPLE);
		listSplit.setDividerPositions(0.5);
		editor.init(smTemplates.selectedItemProperty(), saveBtn);

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
				FxAlert.showErrorDialog(n.jsonFilePath, "failed loading file", n.error());
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
		FileOpenerNE.openFileLocationInExplorer(json().jsonFilePath.toFile());
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
				FxAlert.showErrorDialog(j.jsonFilePath, "failed loading file", j.error());
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
	public static String relativeToSnippedDir(Path path) {
		return "%SNIPPET_DIR%\\".concat(path.subpath(snippet_dir_count, path.getNameCount()).toString());
	}
	public static String relativeToSelfDir(Path path) {
		return "%SELF_DIR%\\".concat(path.subpath(self_dir_count, path.getNameCount()).toString());
	}
	public static Window stage() {
		return stage;
	}
}
