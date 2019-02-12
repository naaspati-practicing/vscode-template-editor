

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;

import javafx.application.Application;
import sam.pkg.App;
import sam.pkg.Utils;

public class Main {
	public static void main(String[] args) throws URISyntaxException, IOException {
		Files.createDirectories(Utils.CACHE_DIR);
		Application.launch(App.class, args);
	}
}
