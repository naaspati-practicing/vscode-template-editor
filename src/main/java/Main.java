

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Application;
import sam.config.LoadConfig;
import sam.console.ANSI;
import sam.fx.helpers.ErrorApp;
import sam.myutils.System2;
import sam.pkg.App;

public class Main {
	public static void main(String[] args) throws URISyntaxException, IOException {
		try {
			LoadConfig.load();
			Path appdir = Paths.get(Optional.ofNullable(System2.lookup("APP_DATA")).orElse("app_data"));

			String sdr = System2.lookup("snippets_dir");

			if(sdr == null) {
				errorStage("var not set: snippets_dir", null);
				return;
			}

			Path snidir = Paths.get(sdr);
			if(!Files.isDirectory(snidir)) {
				errorStage("dir not found (snippets_dir)\n"+sdr, null);
				return;
			}

			Files.createDirectories(appdir);
			FileChannel fc = FileChannel.open(appdir.resolve("app_lock"), CREATE, WRITE);

			FileLock lock = fc.tryLock();
			if(lock == null)
				errorStage("ONE ONE INSTANCE ALLOWED", null);
			else {
				Logger LOGGER = logger();

				System.getProperties().put("APP_DIR", appdir);
				System.getProperties().put("SNIPPETS_DIR", snidir);

				LOGGER.debug(ANSI.yellow("APP_DIR:") +"\""+ appdir.toAbsolutePath()+"\"");
				LOGGER.info(ANSI.yellow("SNIPPETS_DIR: ")+"\""+ snidir.toAbsolutePath()+"\"");
				Application.launch(App.class, args);
			}
		} catch (Throwable e) {
			errorStage(null, e);
		}
	}

	private static void errorStage(String title, Throwable e) {
		if(e != null)
			logger().error("{}", title == null ? "" : title, e);

		ErrorApp.set(title, e);
		Application.launch(ErrorApp.class, new String[0]);	
	}

	private static Logger logger() {
		return LoggerFactory.getLogger(Main.class);
	}
}
