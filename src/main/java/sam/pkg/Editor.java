package sam.pkg;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.text.Text;
import sam.fx.helpers.FxButton;
import sam.fx.helpers.FxConstants;
import sam.fx.helpers.FxGridPane;
import sam.fx.helpers.FxHBox;
import sam.fx.helpers.FxMenu;
import sam.fx.helpers.FxText;
import sam.fx.helpers.IconButton;
import sam.pkg.jsonfile.api.Template;

public class Editor extends BorderPane  {
	private static final Logger LOGGER = LoggerFactory.getLogger(Editor.class);
	
	private final GridPane center = FxGridPane.gridPane(5);

	private final TextField idTF = new TextField();
	private final TextField prefixTF = new TextField();
	private final TextArea descriptionTA = new TextArea();
	private final TextArea bodyTA = new TextArea();
	private final Button saveBtn = FxButton.button("SAVE", this::saveAction);
	private final Node bottom = FxHBox.buttonBox(saveBtn);

	private final TextArea fullTa = new TextArea();
	private final Button backBtn = FxButton.button("BACK", e -> backAction());
	private History history;
	private InvalidationListener modListener = i -> mod(1);
	private final FullScreen fullScreen;

	public Editor(ReadOnlyObjectProperty<Template> currentItem, FullScreen fullScreen) {
		this.fullScreen = fullScreen;
		setId("editor");
		
		IconButton expandMore = new IconButton();
		expandMore.setIcon("full-size.png");
		expandMore.setFitHeight(15);
		expandMore.setOnAction(e -> expandMore());
		
		setTop(FxHBox.buttonBox(backBtn, expandMore, FxHBox.maxPane(), title("Editor")));
		backBtn.setVisible(false);
		expandMore.visibleProperty().bind(backBtn.visibleProperty());

		BorderPane.setMargin(fullTa, FxConstants.INSETS_5);
		BorderPane.setMargin(center, FxConstants.INSETS_5);
		int row = 0;

		center.addRow(row++, text("ID"), idTF);
		center.addRow(row++, text("prefix"), prefixTF);
		row = textArea(center, row, 3, "description", descriptionTA, DESCRIPTION);
		row = textArea(center, row, 3, "body", bodyTA, BODY);

		ColumnConstraints c = new ColumnConstraints();
		c.setFillWidth(true);
		c.setMaxWidth(Double.MAX_VALUE);
		c.setHgrow(Priority.ALWAYS);
		FxGridPane.setColumnConstraint(center, 1, c);

		RowConstraints r = new RowConstraints();
		r.setFillHeight(true);
		r.setVgrow(Priority.ALWAYS);
		FxGridPane.setRowConstraint(center, GridPane.getRowIndex(bodyTA), r);

		idTF.setEditable(false);

		setCenter(center);
		setBottom(bottom);
		setDisable(true);
		
		currentItem.addListener((p, o, n) -> setItem(n));
	}
	
	private void expandMore() {
		setCenter(null);
		fullScreen.fullscreen(fullTa, "Edit: "+current.id()+"'s " +(history.view == BODY ? "body" : "description"), () -> setCenter(fullTa));
	}

	public boolean isModified() {
		if(current == null)
			return false;
		return 
				!Objects.equals(current.prefix(), prefix()) ||
				!Objects.equals(current.description(), desp()) ||
				!Objects.equals(current.body(), body());
				
	}
	
	private boolean not_mod = true, listener = false;

	private void mod(int n) {
		history.mod += n;
		boolean b = history.mod == 0;
		
		if(not_mod != b) {
			not_mod = b;
			saveBtn.setDisable(not_mod);	
		}
		
		if(not_mod && !listener) {
			prefixTF.textProperty().addListener(modListener);
			descriptionTA.textProperty().addListener(modListener);
			bodyTA.textProperty().addListener(modListener);
			listener = true;
			LOGGER.debug("listener added");
		} else if(listener) {
			prefixTF.textProperty().removeListener(modListener);
			descriptionTA.textProperty().removeListener(modListener);
			bodyTA.textProperty().removeListener(modListener);
			listener = false;
			LOGGER.debug("listener removed");
		}
	}

	private int textArea(GridPane center,  int row, Integer rowSpan, String title, TextArea node, int view) {
		IconButton b = new IconButton();
		b.setIcon("full-size.png");
		b.setFitHeight(10);
		b.setOnAction(e -> setView(view));
		b.disableProperty().bind(disableProperty());

		center.addRow(row++, text(title), b);
		center.addRow(row++, node);

		GridPane.setHalignment(b, HPos.RIGHT);
		GridPane.setColumnSpan(b, GridPane.REMAINING);
		GridPane.setColumnSpan(node, GridPane.REMAINING);
		GridPane.setRowSpan(node, rowSpan);

		if(rowSpan != GridPane.REMAINING) {
			row += rowSpan;
			node.setPrefRowCount(rowSpan);
		}
		return row;
	}

	private void backAction() {
		backBtn.setVisible(false);
		ta(history.view).setText(fullTa.getText());
		history.view = EDITOR;

		setCenter(center);
		setBottom(bottom);
	}
	private TextArea ta(int type) {
		switch (type) {
			case BODY: return bodyTA;
			case DESCRIPTION: return descriptionTA;
			default:
				throw new RuntimeException();
		}
	}

	private void setView(int view) {
		if(view == EDITOR) {
			if(center != getCenter())
				backAction();
		} else {
			backBtn.setVisible(true);
			fullTa.setText(ta(view).getText());

			setCenter(fullTa);
			setBottom(null);
		}
		history.view = view;
	}

	private Node text(String s) {
		return FxText.text(s, "text");
	}

	private Node title(String title) {
		Text t = FxText.text(title, "title");
		BorderPane.setMargin(t, FxConstants.INSETS_5);
		BorderPane.setAlignment(t, Pos.CENTER_LEFT);

		return t;
	}

	private static final int EDITOR = 0;
	private static final int DESCRIPTION = 0x777;
	private static final int BODY = 0x333;

	private class History {
		String prefix, desp, body;
		int view;
		int mod;

		void update() {
			this.prefix = prefix();
			this.desp = desp();
			this.body = body();
		}

		public void restore() {
			prefixTF.setText(prefix);
			descriptionTA.setText(desp);
			bodyTA.setText(body);
		}
	}

	@FXML
	private void saveAction(ActionEvent e) {
		if(isModified())  {
			current.prefix(prefix());
			current.body(body());
			current.description(desp());

			current.save();
			history.update();
		}
		
		history.mod = 0;
		mod(0);
	}

	private static final Map<Template, History> CACHE = new IdentityHashMap<>();
	private Template current;
	private Function<Template, History> computer = i -> new History();

	private void setItem(Template n) {
		if(current != null) 
			history.update();

		current = n;

		if(n == null) {
			set(idTF, null);
			set(prefixTF, null);
			set(descriptionTA, null);
			set(bodyTA, null);
			history = null;

			this.setDisable(true);
		} else {
			this.setDisable(false);
			history = CACHE.computeIfAbsent(n, computer);

			if(history.mod != 0)
				history.restore();
			else {
				prefixTF.setText(n.prefix());
				descriptionTA.setText(n.description());
				bodyTA.setText(n.body());	
			}

			idTF.setText(n.id());
			setView(history.view);
			mod(0);
		}
	}
	private void set(TextInputControl t, String s) {
		t.setText(s);
		t.setUserData(s);
	}
	private String desp() {
		return descriptionTA.getText();
	}
	private String body() {
		return bodyTA.getText();
	}
	private String prefix() {
		return prefixTF.getText();
	}
}
