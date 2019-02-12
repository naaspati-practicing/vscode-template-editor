package sam.pkg.jsonfile;

import static sam.pkg.Utils.CACHE_DIR;
import static sam.pkg.Utils.SNIPPET_DIR;
import static sam.pkg.Utils.subpathWithPrefix;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sam.io.fileutils.FilesUtilsIO;
import sam.io.serilizers.ObjectReader;
import sam.io.serilizers.ObjectWriter;

public class JsonFiles implements Closeable {
	private final List<JsonFile> files;
	private final Path cacheFilePath = CACHE_DIR.resolve("cache_meta.dat");

	@SuppressWarnings({ "rawtypes" })
	public JsonFiles() throws IOException {
		List<CacheFile> cacheFiles = Files.notExists(cacheFilePath) ? Collections.emptyList() : ObjectReader.readList(cacheFilePath, CacheFile::new);

		if(cacheFiles.isEmpty())
			FilesUtilsIO.deleteDir(CACHE_DIR);
		
		Files.createDirectories(CACHE_DIR);
		
		Map<Path, CacheFile> map = cacheFiles.isEmpty() ? Collections.emptyMap() : cacheFiles.stream().collect(Collectors.toMap(CacheFile::getSourcePath, c -> c));
		this.files = new ArrayList<>();  
		
		Iterator<Path> itr = Files.walk(SNIPPET_DIR)
		.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"))
		.iterator();
		
		while (itr.hasNext()) {
			Path f = itr.next();
			CacheFile c = map.get(f);
			if(c == null) {
				c = new CacheFile(f, -1);
				System.out.println("new Json: "+subpathWithPrefix(f));
			}
			files.add(new JsonFile(c));
		}
		files.sort(Comparator.comparingLong(j -> j.getCacheFile().getLastModified() < 0 ? Long.MAX_VALUE : j.getCacheFile().getLastModified()));
		((ArrayList)files).trimToSize();
	}

	@Override
	public void close() throws IOException {
		files.forEach(jf -> {
			try {
				jf.close();
			} catch (IOException e) {
				System.err.println(jf.getSourcePath());
				e.printStackTrace();
			}
		});
		if(files.stream().anyMatch(JsonFile::isModified))
			ObjectWriter.writeList(cacheFilePath, files, (f, dos) -> f.getCacheFile().write(dos));
	}

	public List<JsonFile> getFiles() {
		return Collections.unmodifiableList(files);
	}
}
