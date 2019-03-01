package sam.pkg.jsonfile;

import static sam.pkg.Utils.SNIPPET_DIR;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import sam.myutils.Checker;
import sam.myutils.MyUtilsPath;
class CacheFile {
	private long lastModified;
	private final Path subpath;
	private final Path source;

	public CacheFile(Path jsonSource, long lastModified) throws IOException {
		Checker.requireNonNull("jsonSource", jsonSource);
		
		this.source = jsonSource;
		this.subpath = MyUtilsPath.subpath(jsonSource, SNIPPET_DIR);
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
	public long getLastModified() {
		return lastModified;
	}
	public boolean isSourceModified() {
		return lastModified != source.toFile().lastModified();
	}
	public Path subpath() {
		return subpath;
	}
	public void updateLastModified() {
		this.lastModified = source.toFile().lastModified();
	}
}
