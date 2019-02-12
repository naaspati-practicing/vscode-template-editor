package sam.pkg.jsonfile;

import static sam.pkg.Utils.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import sam.myutils.Checker;
import sam.myutils.MyUtilsPath;
import sam.pkg.Utils;
class CacheFile {
	private long lastModified;
	private final Path subpath;
	private final Path source;
	private final Path cachePath;

	public CacheFile(Path jsonSource, long lastModified) throws IOException {
		Checker.requireNonNull("jsonSource", jsonSource);
		
		this.source = jsonSource;
		this.subpath = MyUtilsPath.subpath(jsonSource, SNIPPET_DIR);
		this.cachePath = CACHE_DIR.resolve(subpath); 
		this.lastModified = lastModified;
	}
	
	public CacheFile(DataInputStream dis) throws IOException {
		this(SNIPPET_DIR.resolve(dis.readUTF()), dis.readLong());
	}
	public Path getSourcePath() {
		return source;
	}
	public void write(DataOutputStream dos) throws IOException {
		dos.writeUTF(subpath.toString() );
		dos.writeLong(lastModified);
	}
	public void clear() {
		try {
			Utils.delete(cachePath);
			Utils.delete(resolve(".index"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private Path resolve(String ext) {
		return cachePath.resolveSibling(cachePath.getFileName()+ext);
	}
	public long getLastModified() {
		return lastModified;
	}
	public boolean exists() {
		return Files.exists(cachePath);
	}

	public boolean isSourceModified() {
		return lastModified != source.toFile().lastModified();
	}

	public Path subpath() {
		return subpath;
	}

	public Path keysPath() {
		return resolve(".keys");
	}

	public Path cachePath() {
		return cachePath;
	}

	public void updateLastModified() {
		this.lastModified = source.toFile().lastModified();
	}
}
