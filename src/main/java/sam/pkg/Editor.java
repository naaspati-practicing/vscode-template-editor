package sam.pkg;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.pkg.JsonFile.BODY;
import static sam.pkg.JsonFile.DESCRIPTION;
import static sam.pkg.JsonFile.PREFIX;
import static sam.pkg.JsonFile.get;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Objects;

import org.json.JSONObject;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import sam.fx.helpers.FxFxml;
import sam.fx.helpers.FxGridPane;
import sam.fx.helpers.FxHBox;
import sam.fx.helpers.FxUtils;
import sam.reference.WeakAndLazy;
// import sam.fx.helpers.IconButton;
public class Editor extends GridPane  {
	@FXML private TextField idTF;
	@FXML private TextField prefixTF;
	@FXML private TextArea descriptionTA;
	@FXML private TextArea bodyTA;
	private Button _saveBtn;

	public Editor() throws IOException {
		FxFxml.load(this, true);
		ColumnConstraints c = new ColumnConstraints();
		c.setFillWidth(true);
		c.setHgrow(Priority.ALWAYS);
		FxGridPane.setColumnConstraint(this, 1, c);
		RowConstraints r = new RowConstraints();
		r.setFillHeight(true);
		r.setVgrow(Priority.ALWAYS);
		FxGridPane.setRowConstraint(this, GridPane.getRowIndex(bodyTA), r);
		
		setDisable(true);
	}

	private class Changes {
		final String prefix, desp, body;

		public Changes() {
			this.prefix = prefix();
			this.desp = desp();
			this.body = body();
		}
	}

	private void saveAction(Event e) {
		currentJO.put(PREFIX, prefix());
		currentJO.put(BODY, body());
		currentJO.put(DESCRIPTION, desp());

		current.setSaved();
	}

	private static final IdentityHashMap<Key, Changes> CACHE = new IdentityHashMap<>();
	private Key current;
	private JSONObject currentJO;

	private void change(Key n) {
		if(current != null) 
			CACHE.put(current, new Changes());

		current = n;

		if(n == null) {
			set(idTF, null);
			set(prefixTF, null);
			set(descriptionTA, null);
			set(bodyTA, null);

			currentJO = null;
			this.setDisable(true);

		} else {
			JSONObject o = n.object();
			currentJO = o;
			this.setDisable(false);

			prefixTF.setText(get(o, PREFIX));
			descriptionTA.setText(get(o, DESCRIPTION));
			bodyTA.setText(get(o, BODY));	

			idTF.setText(n.entryId);
			Changes c = CACHE.get(n);

			if(c != null) {
				prefixTF.setText(c.prefix);
				descriptionTA.setText(c.desp);
				bodyTA.setText(c.body);
			}

			statusCheck(null);
		}
	}
	private void set(TextInputControl t, String s) {
		t.setText(s);
		t.setUserData(s);
	}

	private void statusCheck(Object o) {
		if(_saveBtn == null) return;
		
		_saveBtn.setDisable(
				empty(prefix()) || 
				empty(body()) || (
						eq(prefixTF) &&
						eq(bodyTA) &&
						eq(descriptionTA)
						) 
				);		
	}
	private boolean eq(TextInputControl t) {
		return Objects.equals(t.getText(), t.getUserData());
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
	private boolean empty(String text) {
		return isEmptyTrimmed(text);
	}
	public void init(ReadOnlyObjectProperty<Key> selectedItemProperty, Button saveBtn) {
		selectedItemProperty.addListener((p, o, n) -> change(n));
		this._saveBtn = saveBtn;
		if(saveBtn != null) {
			saveBtn.setOnAction(this::saveAction);
			FxUtils.each(t -> t.textProperty().addListener(this::statusCheck), prefixTF, descriptionTA, bodyTA);
		} else {
			FxUtils.each(t -> t.setEditable(false), prefixTF, descriptionTA, bodyTA);
		}
	}
	
	private static class Expand extends Stage implements EventHandler<ActionEvent> {
		final TextArea ta = new TextArea();
		private TextArea body;
		
		public Expand() {
			initModality(Modality.APPLICATION_MODAL);
			initStyle(StageStyle.UTILITY);
			
			Button ok = new Button("OK");
			Button cancel = new Button("CANCEL");
			cancel.setCancelButton(true);
			cancel.setOnAction(e -> hide());
			ok.setOnAction(this);
			
			setScene(new Scene(new BorderPane(ta, null, null, FxHBox.buttonBox(ok, cancel), null)));
		}
		public void init(Window owner, TextArea body) {
			this.body = body;
			initOwner(owner);
			ta.setText(body.getText());
			setWidth(owner.getWidth());
			setHeight(owner.getHeight());
			setX(owner.getX());
			setY(owner.getY());
			
			setTitle("Body");
			
			show();
		}
		
		@Override
		public void handle(ActionEvent event) {
			body.setText(ta.getText());
			hide();
			body = null;
		}
	}
	
	private WeakAndLazy<Expand> expand = new WeakAndLazy<>(Expand::new);

	@FXML
	private void bodyExpandAction(Event event) {
		Expand e = expand.get();
		e.init(Main.stage(), bodyTA);
	}



}
