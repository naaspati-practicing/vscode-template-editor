package sam.pkg;

import java.io.IOException;
import java.util.Collection;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import sam.fx.helpers.FxBindings;
import sam.fx.helpers.FxCell;
import sam.fx.helpers.FxFxml;
import sam.fx.helpers.FxTextSearch;
import sam.myutils.Checker;
import sam.pkg.JsonFile.Template;

public class Adder {
	private Stage stage = new Stage();
	@FXML private Editor editor;
	@FXML private ListView<TemplateWrap> similar;
	@FXML private TextField search;
	@FXML private Button ok;
	private FxTextSearch<TemplateWrap> searcher = new FxTextSearch<>(f -> f.key_lower, 300, true);
	
	public Adder(Window owner) throws IOException {
		FxFxml.load(this, stage, this);
		
		stage.initModality(Modality.APPLICATION_MODAL);
		stage.initOwner(owner);
		stage.initStyle(StageStyle.UTILITY);
		
		ok.setOnAction(this::okAction);
		
		similar.setCellFactory(FxCell.listCell(k -> k.key));
		editor.init(FxBindings.<TemplateWrap, Template>map(similar.getSelectionModel().selectedItemProperty(), t -> t.template), null);
		editor.setDisable(true);
	}
	
	private String result;
	private final Runnable onchange = () -> {
		searcher.applyFilter(similar.getItems());
		Platform.runLater(() -> {
			String s = search.getText();
			ok.setDisable(Checker.isEmptyTrimmed(s) || similar.getItems().stream().anyMatch(f -> f.template.id().equals(s)));
		});
	};
	//TODO
	public String newId(Collection<Template> allkeys) {
		similar.getItems().setAll(allkeys);
		searcher.setAllData(allkeys);
		searcher.setOnChange(onchange);
		
		InvalidationListener iv = i -> searcher.addSearch(search.getText());
		search.textProperty().addListener(iv);
		
		stage.showAndWait();
		result = Checker.isEmptyTrimmed(result) ? null : result;
		search.textProperty().removeListener(iv);
		searcher.setOnChange(null);
		searcher.setAllData(null);
		search.setText(null);
		
		return result;
	}
	private void okAction(ActionEvent e) {
		result = search.getText();
		stage.hide();
	}
	
	private class TemplateWrap {
		final String key;
		final String key_lower;
		final Template template;
		
		TemplateWrap(String key, Template template) {
			this.template = template;
			this.key = key;
			this.key_lower = key.toLowerCase();
		}
	}
}
