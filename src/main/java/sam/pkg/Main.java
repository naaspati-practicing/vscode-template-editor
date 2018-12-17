package sam.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import sam.console.ANSI;
import sam.fx.alert.FxAlert;
import sam.fx.helpers.FxFxml;
import sam.fx.popup.FxPopupShop;
import sam.io.fileutils.FileOpenerNE;
import sam.io.fileutils.FilesUtilsIO;
import sam.io.serilizers.ObjectReader;
import sam.io.serilizers.ObjectWriter;
import sam.myutils.MyUtilsException;
import sam.myutils.System2;
import sam.pkg.JsonFile.Template;

// import sam.fx.helpers.FxConstants;

public class Main extends Application {
	private final static Path SNIPPET_DIR = Paths.get(System2.lookup("snippets_dir"));

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

	public static final Path SELF_DIR = Paths.get(System2.lookup("SELF_DIR"));
	public static final Path CACHE_DIR = SELF_DIR.resolve("cache");
	private final Path cache_meta_path = SELF_DIR.resolve("cache_meta.dat");

	MultipleSelectionModel<JsonFile> smJson;
	MultipleSelectionModel<Template> smTemplates;
	private static Stage stage;

	@Override
	public void start(Stage stage) throws Exception {
		Main.stage = stage;
		FxFxml.load(this, stage, this);
		System.out.println(ANSI.yellow("SNIPPET_DIR: ")+SNIPPET_DIR);
		System.out.println(ANSI.yellow("SELF_DIR: ")+SELF_DIR);

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

		load();
	}
	@SuppressWarnings("resource")
	private void load() throws IOException {
		int maxId[] = {0};

		final HashMap<Path, JsonFile> jsonFiles = new HashMap<>();

		if(Files.exists(cache_meta_path)) {
			ObjectReader.iterate(cache_meta_path, dis -> {
				JsonFile c = new JsonFile(dis);
				if(!c.exists())
					return;
				
				jsonFiles.put(c.jsonFilePath, c);
				maxId[0] = Math.max(maxId[0], c.id); 
			});
			maxId[0]++;
		} else {
			FilesUtilsIO.deleteDir(CACHE_DIR);
		}

		Files.createDirectories(CACHE_DIR);

		Files.walk(SNIPPET_DIR)
		.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"))
		.forEach(f -> {
			jsonFiles.computeIfAbsent(f, f2 -> {
				System.out.println("new Json: "+relativeToSnippedDir(f));
				return new JsonFile(maxId[0]++, f, f.toFile().lastModified(), false, true);	
			});
		});

		jsonFiles.values()
		.stream()
		.sorted(Comparator.<JsonFile>comparingLong(c -> c.lastModified()).reversed())
		.forEach(jsonFiles()::add);
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
		jsonFiles().forEach(jf -> {
			try {
				jf.close();
			} catch (IOException e) {
				System.err.println(jf.jsonFilePath);
				e.printStackTrace();
			}
		});
		ObjectWriter.writeList(cache_meta_path, jsonFiles(), JsonFile::write);
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

	public static final int snippet_dir_count = SNIPPET_DIR.getNameCount();
	public static String relativeToSnippedDir(Path path) {
		return "%SNIPPET_DIR%\\".concat(path.subpath(snippet_dir_count, path.getNameCount()).toString());
	}
	public static final int self_dir_count = SELF_DIR.getNameCount(); 
	public static String relativeToSelfDir(Path path) {
		return "%SELF_DIR%\\".concat(path.subpath(self_dir_count, path.getNameCount()).toString());
	}
	public static Window stage() {
		return stage;
	}
}
