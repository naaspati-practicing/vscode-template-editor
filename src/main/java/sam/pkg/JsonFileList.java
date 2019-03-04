package sam.pkg;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import sam.fx.helpers.FxButton;
import sam.fx.helpers.FxCell;
import sam.io.fileutils.FileOpenerNE;
import sam.pkg.jsonfile.api.JsonFile;

public class JsonFileList extends ListView2<JsonFile> {
	private final Button openButton = FxButton.button("Open", this::openAction);
	private final TemplateList templateList;

	public JsonFileList(TemplateList templateList) {
		super("Json Files");
		this.templateList = templateList;

		selectedItemProperty().addListener((p, o, n) -> {
			if(n == null)
				templateList.setDisable(true);
			else  {
				
				n.getTemplates((list,  error) -> {
					if(error != null) {
						templateList.setItems(null);
						//TODO
					} else 
						templateList.setItems(list);
				});
			}
				
		});
		list.setCellFactory(FxCell.listCell(JsonFile::toString));
		openButton.disableProperty().bind(selectedItemProperty().isNull());
	}
	@Override
	protected Node[] buttons() {
		return new Node[]{openButton};
	}
	private void openAction(Event e) {
		FileOpenerNE.openFileLocationInExplorer(selected().source().toFile());
	}
	/*
	 * private void jsonChange(JsonFile n) {
		smTemplates.clearSelection();
		if(n == null)
			entries.clear();
		else {
			n.getTemplates((list, error) -> {
				if(error != null) {
					FxAlert.showErrorDialog(n.source, "failed loading file", error);
					smJson.clearSelection();
					jsonFiles().remove(n);
					// FIXME replace editor with error TextArea 
				} else {
					entries.setAll(list);					
				}
			});
		}
	}
	 */
	
	private static final Map<JsonFile, String> names = new IdentityHashMap<>();
	
	@Override
	protected String searchValue(JsonFile t) {
		return names.get(t);
	}
	
	@Override
	public void setItems(List<JsonFile> data) {
		super.setItems(data);
		data.forEach(d -> names.put(d, d.source().getFileName().toString().toLowerCase()));
	}
	
}
	