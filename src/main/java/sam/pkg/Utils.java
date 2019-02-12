package sam.pkg;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import sam.config.LoadConfig;
import sam.console.ANSI;
import sam.myutils.System2;

public class Utils {
	private Utils() { }
	
	public final static Path SNIPPET_DIR;
	public static final Path APP_DIR = Paths.get("app_data");
	public static final Path CACHE_DIR = APP_DIR.resolve("cache");
	private static final int cacheDirCount, snipDirCount;

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
		cacheDirCount = CACHE_DIR.getNameCount();
		snipDirCount = SNIPPET_DIR.getNameCount();
	}
	
	public static void delete(Path p) throws IOException {
		if(Files.deleteIfExists(p))
			System.out.println("DELETED: "+p);
	}
	public static void write(CharSequence s, FileChannel os, ByteBuffer buffer, CharsetEncoder encoder) throws IOException {
		CharBuffer chars = CharBuffer.wrap(s);

		while(true) {
			CoderResult c = encoder.encode(chars, buffer, true);
			write(buffer, os);

			if(!chars.hasRemaining()) {
				while(true) {
					c = encoder.flush(buffer);
					write(buffer, os);
					if(c.isUnderflow()) break;
				}
				break;
			}
		}
		buffer.clear();
	}
	public static void write(ByteBuffer buffer, FileChannel os) throws IOException {
		if(buffer.position() != 0)
			buffer.flip();
		else
			System.out.println("Utils: buffer not flipped");
		
		System.out.println("Utils: writing buffer("+buffer.remaining()+")");

		while(buffer.hasRemaining())
			os.write(buffer);

		buffer.clear();
	}
	public static String subpathWithPrefix(Path path) {
		int n = path.getNameCount(); 
		if(n > cacheDirCount && path.startsWith(CACHE_DIR))
			return "cache://"+path.subpath(cacheDirCount, n);
		if(n > snipDirCount && path.startsWith(SNIPPET_DIR))
			return "snippetDir://"+path.subpath(snipDirCount, n);
		
		return path.toString();
	}
	
	public static Path subpath(Path path) {
		int n = path.getNameCount(); 
		if(n > cacheDirCount && path.startsWith(CACHE_DIR))
			return path.subpath(cacheDirCount, n);
		if(n > snipDirCount && path.startsWith(SNIPPET_DIR))
			return path.subpath(snipDirCount, n);
		return path;
	}
}
