package sam.pkg.jsonfile;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import sam.io.fileutils.FilesUtilsIO;
import sam.io.serilizers.ObjectReader;
import sam.io.serilizers.ObjectWriter;
import sam.logging.MyLoggerFactory;
import sam.myutils.MyUtilsException;
import sam.pkg.App;
import sam.pkg.Main;

public class JsonFiles implements Closeable {
	private static final Logger LOGGER = MyLoggerFactory.logger(JsonFiles.class);
	private final List<JsonFile> files;
	private final Path cache_meta_path = Main.CACHE_DIR.resolve("cache_meta.dat");

	@SuppressWarnings("resource")
	public JsonFiles() throws IOException {
		int maxId[] = {0};

		final HashMap<Path, JsonFile> jsonFiles = new HashMap<>();

		if(Files.exists(cache_meta_path)) {
			ObjectReader.iterate(cache_meta_path, dis -> {
				int id = dis.readInt();
				long last_modified = dis.readLong();
				Path path = Paths.get(dis.readUTF());
				
				JsonFile c = new JsonFile(id, path, last_modified);
				if(!c.isFileExists())
					return;

				jsonFiles.put(c.jsonFilePath, c);
				maxId[0] = Math.max(maxId[0], c.id); 
			});
			maxId[0]++;
		} else {
			FilesUtilsIO.deleteDir(Main.CACHE_DIR);
		}

		Files.createDirectories(Main.CACHE_DIR);
		Files.walk(Main.SNIPPET_DIR)
		.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"))
		.forEach(f -> {
			jsonFiles.computeIfAbsent(f, f2 -> {
				LOGGER.info(() -> "new Json: "+App.relativeToSnippedDir(f));
				return MyUtilsException.noError(() -> new JsonFile(maxId[0]++, f, 0L));	
			});
		});

		this.files =  jsonFiles.values()
				.stream()
				.sorted(Comparator.<JsonFile>comparingLong(c -> c.lastModified()).reversed())
				.collect(Collectors.toList());
	}

	@Override
	public void close() throws IOException {
		files.forEach(jf -> {
			try {
				jf.close();
			} catch (IOException e) {
				System.err.println(jf.jsonFilePath);
				e.printStackTrace();
			}
		});
		
		ObjectWriter.writeList(cache_meta_path, files, (f, dos) -> {
			dos.writeInt(f.id);
			dos.writeLong(f.lastModified());
			dos.writeUTF(f.jsonFilePath.toString());
		});
	}

	public List<JsonFile> getFiles() {
		return Collections.unmodifiableList(files);
	}


}
