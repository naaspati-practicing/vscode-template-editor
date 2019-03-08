package sam.pkg.jsonfile.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public interface JsonManager extends AutoCloseable {
	List<JsonFile> getFiles();
	
	static Stream<Path> walk(Path snippetDir) throws IOException {
		return Files.walk(snippetDir)
				.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"));
	}
}
