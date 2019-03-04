package sam.pkg;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import sam.fx.alert.FxAlert;
import sam.fx.helpers.FxButton;
import sam.fx.helpers.FxConstants;
import sam.fx.helpers.FxHBox;
import sam.fx.popup.FxPopupShop;
import sam.myutils.MyUtilsException;
import sam.pkg.jsonfile.api.JsonManager;
import sam.thread.MyUtilsThread;

// import sam.fx.helpers.FxConstants;

public class App extends Application implements FullScreen {
	private final TemplateList entries = new TemplateList();
	private final JsonFileList listJson = new JsonFileList(entries);
	private final Editor editor = new Editor(entries.selectedItemProperty(), this);

	private final HBox root = new HBox(5, new SplitPane(listJson, entries), editor);
	private final Scene mainScene = new Scene(root);
	private JsonManager jsons;

	public final Path app_dir = syspath("APP_DIR");
	public final Path snippet_dir = syspath("SNIPPETS_DIR");

	private Path syspath(String key) {
		return Objects.requireNonNull((Path)System.getProperties().get(key));
	}

	@Override
	public void start(Stage stage) throws Exception {
		stage.setTitle("VSCode template Editor");

		root.setFillHeight(true);

		FxPopupShop.setParent(stage);
		FxAlert.setParent(stage);

		HBox.setHgrow(editor, Priority.ALWAYS);
		editor.setMaxWidth(Double.MAX_VALUE);

		stage.setScene(mainScene);
		mainScene.getStylesheets().add("styles.css");
		stage.show();

		MyUtilsThread.runOnDeamonThread(() -> {
			try {
				
				String cname = JsonManager.class.getName();
				String s = System.getProperty(cname);
				if(s == null) {
					InputStream is = getClass().getResourceAsStream(cname);
					System.out.println(cname);

					if(is == null) {
						File file = new File(cname);
						if(file.exists())
							is = new FileInputStream(file);
					}
					if(is == null)
						is = ClassLoader.getSystemResourceAsStream(cname);
					
					if(is == null)
						throw new IllegalStateException("no implemention found for: "+cname);

					s = new BufferedReader(new InputStreamReader(is))
							.lines()
							.map(String::trim)
							.filter(t -> !t.isEmpty() && t.charAt(0) != '#')
							.findFirst()
							.orElseThrow(() -> new IllegalStateException("no implemention found for: "+cname));
				}

				JsonManager jsons = (JsonManager) Class.forName(s)
						.getConstructor(Path.class)
						.newInstance(snippet_dir);
				
				logger().debug("Implementation found: "+cname+" -> "+jsons);

				Platform.runLater(() -> {
					this.jsons = jsons;
					mainScene.setRoot(root);
					listJson.setItems(jsons.getFiles());
					addClosedHandler(stage);
				});
			} catch (Throwable e2) {
				Platform.runLater(() -> failed(stage, "failed ", e2));
			}
		});
	}

	private void addClosedHandler(Stage stage) {
		stage.setOnCloseRequest(ea -> {
			if(jsons != null) {
				try {
					jsons.close();
				} catch (Throwable e) {
					failed(stage, "failed to close JsonManager", e);
					jsons = null;
					ea.consume();
				}	
			}
		});
	}

	private void failed(Stage stage, String title, Throwable e) {
		logger().error(title, e);

		stage.setTitle(title);
		StringBuilder sb = new StringBuilder();
		MyUtilsException.append(sb, e, true);
		mainScene.setRoot(new TextArea(sb.toString()));
	}

	private Logger logger() {
		return LoggerFactory.getLogger(App.class);
	}

	private Button backBtn;
	private BorderPane fullscreen;
	private Text fullscreen_title;

	private Runnable backaction;

	private void backAction() {
		mainScene.setRoot(root);
		fullscreen.setCenter(null);
		Platform.runLater(backaction);
		backaction = null;
	}
	@Override
	public void fullscreen(Node node, String title, Runnable backaction) {
		if(fullscreen == null) {
			backBtn = FxButton.button("BACK", e -> backAction());
			fullscreen = new BorderPane();
			fullscreen_title = new Text();

			fullscreen.setTop(FxHBox.buttonBox(backBtn, FxHBox.maxPane(), fullscreen_title));
			BorderPane.setMargin(backBtn, FxConstants.INSETS_5);
			BorderPane.setAlignment(backBtn, Pos.CENTER_LEFT);
		}

		Objects.requireNonNull(node);
		Objects.requireNonNull(backaction);

		fullscreen_title.setText(title);
		this.backaction = backaction;
		fullscreen.setCenter(node);

		mainScene.setRoot(fullscreen);
	}
}
