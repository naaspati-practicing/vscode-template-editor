package sam.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Utils {
	private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
	
	private Utils() { }
	
	public static final Path APP_DIR = syspath("APP_DIR");
	public static final Path SNIPPET_DIR = syspath("SNIPPET_DIR");
	
	private static Path syspath(String key) {
		return Objects.requireNonNull((Path)System.getProperties().get(key));
	}
	
	public static void delete(Path p) throws IOException {
		if(Files.deleteIfExists(p))
			LOGGER.info("DELETED: {}", p);
	}
}
