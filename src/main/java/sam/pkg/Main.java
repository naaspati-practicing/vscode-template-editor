package sam.pkg;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javafx.application.Application;
import sam.config.LoadConfig;
import sam.console.ANSI;
import sam.logging.MyLoggerFactory;
import sam.myutils.System2;

public class Main {
	public final static Path SNIPPET_DIR;
	public static final Path APP_DIR = Paths.get("app_data");
	public static final Path CACHE_DIR = APP_DIR.resolve("cache");
	

	static {
		try {
			LoadConfig.load();
		} catch (URISyntaxException | IOException e) {
			throw new RuntimeException(e);
		}

		SNIPPET_DIR = Paths.get(System2.lookup("snippets_dir"));
		
		if(Files.notExists(SNIPPET_DIR)) {
			System.out.println("SNIPPET_DIR not found: "+SNIPPET_DIR);
			System.exit(0);
		}
		
		System.out.println(ANSI.yellow("SNIPPET_DIR: ")+SNIPPET_DIR.toAbsolutePath()); 
	}

	public static void main(String[] args) throws URISyntaxException, IOException {
		Files.createDirectories(CACHE_DIR);
		Application.launch(App.class, args);
	}

}
