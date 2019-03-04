package sam.pkg.jsonfile.api;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

public interface JsonFile {
	Path source();
	void getTemplates(BiConsumer<List<Template>, Throwable> consumer);
}
