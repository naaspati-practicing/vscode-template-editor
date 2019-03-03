package sam.pkg;

import java.util.List;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import sam.fx.helpers.FxButton;
import sam.fx.helpers.FxCell;
import sam.io.fileutils.FileOpenerNE;
import sam.pkg.jsonfile.JsonFile;

public class JsonFileList extends ListView2<JsonFile> {
	private final Button openButton = FxButton.button("Open", this::openAction);
	private String[] names;

	public JsonFileList() {
		super("Json Files");
		
		list.setCellFactory(FxCell.listCell(JsonFile::toString));
		openButton.disableProperty().bind(selectedItemProperty().isNull());
	}
	@Override
	protected Node[] buttons() {
		return new Node[]{openButton};
	}
	private void openAction(Event e) {
		FileOpenerNE.openFileLocationInExplorer(selected().source.toFile());
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
	
	
	@Override
	protected String searchValue(JsonFile t) {
		return names[t.id];
	}
	
	@Override
	public void setItems(List<JsonFile> data) {
		super.setItems(data);
		int size = data.isEmpty() ? 0 : data.stream().mapToInt(d -> d.id).max().orElse(0) + 1;
		if(size >  200)
			throw new RuntimeException();
		
		names = new String[size];
		data.forEach(d -> names[d.id] = d.source.getFileName().toString().toLowerCase());
	}
	
}
	