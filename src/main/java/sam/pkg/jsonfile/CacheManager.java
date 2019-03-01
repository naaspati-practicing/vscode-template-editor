package sam.pkg.jsonfile;

import static java.nio.charset.CodingErrorAction.*;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static sam.pkg.Utils.*;
import static sam.pkg.Utils.SNIPPET_DIR;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.collection.IndexedMap;
import sam.console.ANSI;
import sam.io.IOUtils;
import sam.io.fileutils.FilesUtilsIO;
import sam.io.infile.DataMeta2;
import sam.io.infile.TextInFile;
import sam.io.serilizers.ObjectReader;
import sam.io.serilizers.ObjectWriter;
import sam.myutils.Checker;
import sam.nopkg.EnsureSingleton;
import sam.nopkg.Junk;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.JsonFile.Template;

public class CacheManager implements Closeable {
	private static final Logger LOGGER = LoggerFactory.getLogger(CacheManager.class);
	private final EnsureSingleton singleton = new EnsureSingleton();

	public static final int KEYS = 0x777;
	
	private final JsonFile[] files;
	private final TextInFile jumbofile;
	private final AtomicInteger ids = new AtomicInteger(0); //FIXME set maxID 
	private final Path MYDIR = APP_DIR.resolve(CacheManager.class.getName());
	private final BitSet modified = new BitSet();

	@SuppressWarnings({ "rawtypes" })
	public CacheManager() throws IOException {
		singleton.init();
		
		Path jffile = MYDIR.resolve("jumbofile");
		
		Path cfp = cacheFilePath();
		Map<Path, Long> map;
		if(Files.notExists(cfp))
			map =  Collections.emptyMap();
		else {
			map = new HashMap<>();
			Files.lines(cfp)
			.forEach(s -> {
				int n = s.indexOf(' ');
				map.put(SNIPPET_DIR.resolve(s.substring(n+1)), Long.parseLong(s.substring(0, n)));
			});
		}
		
		ArrayList<JsonFile> files = new ArrayList<>();  
		
		Iterator<Path> itr = Files.walk(SNIPPET_DIR)
		.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"))
		.iterator();
		
		int namecount = SNIPPET_DIR.getNameCount();
		Long ZERO = Long.valueOf(0);
		
		while (itr.hasNext()) {
			Path f = itr.next();
			Long c = map.get(f);
			if(c == null) {
				c = ZERO;
				LOGGER.info("new Json: %SNIPPET_DIR%\\", f.subpath(namecount, f.getNameCount()));
			}
			files.add(new JsonFile(this, f, c));
		}
		
		this.files = files.toArray(new JsonFile[files.size()]);
		Arrays.sort(this.files, Comparator.comparingLong(j -> j.getLastModified() < 0 ? Long.MAX_VALUE : j.getLastModified()));
	}

	private Path cacheFilePath() {
		return MYDIR.resolve("cacheFilePath");
	}

	@Override
	public void close() throws IOException {
		for (JsonFile f : files) {
			try {
				f.close();
			} catch (IOException e) {
				LOGGER.warn("failed to close: {}", f, e);
			}
		}
		if(Checker.anyMatch(JsonFile::isModified, files))
			ObjectWriter.writeList(cacheFilePath(), files, (f, dos) -> f.getCacheFile().write(dos));
	}
	//JSON file close
	@Override
	public void close() throws IOException {
		if(hasError() || data == null) return;

		if(modified) {
			Path p = cache.keysPath();
			try(OutputStream is = Files.newOutputStream(p, CREATE,TRUNCATE_EXISTING);
					DataOutputStream dos = new DataOutputStream(is);
					) {
				dos.writeInt(data.size());

				for (Template t : data) {
					dos.writeUTF(t.id);
					dos.writeUTF(t.prefix);

					CacheMeta pos = t.dataMeta == null ? CacheMeta.DEFAULT : t.dataMeta;
					dos.writeInt(pos.position());
					dos.writeInt(pos.size());
				}
				//FIXME append instead of truncate
			}
			System.out.println(ANSI.green("saved: ")+p);
		}
	}

	public List<JsonFile> getFiles() {
		return Collections.unmodifiableList(Arrays.asList(files));
	}

	public StringBuilder loadText(DataMeta2 dm, StringResources r) throws IOException {
		StringBuilder sb = r.sb();
		if(sb.length() != 0)
			throw new IOException("sb.length("+sb.length()+") != 0");
		
		IOUtils.ensureCleared(r.buffer);
		IOUtils.ensureCleared(r.chars);
		
		jumbofile.readText(dm, r.buffer, r.chars, r.decoder, sb, REPORT, REPORT);
		
		r.chars.clear();
		r.buffer.clear();
		return sb;
	}

	public DataMeta2 meta(JsonFile file, int type) {
		
		// TODO Auto-generated method stub
		return Junk.notYetImplemented(); //TODO
	}

	public IndexedMap<DataMeta2> loadMetas(JsonFile file, StringResources r, int type) throws IOException {
		
		IOUtils.ensureCleared(r.buffer);
		IOUtils.ensureCleared(r.chars);
		
		// TODO Auto-generated method stub
		return Junk.notYetImplemented();
	}

	public DataMeta2 write(CharSequence sb, StringResources r) {
		
		IOUtils.ensureCleared(r.buffer);
		int id = ids.incrementAndGet();
		modified.set(id);
		return new DataMeta2(id, jumbofile.write(sb, r.encoder, r.buffer, REPORT, REPORT));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void transfer(List<DataMeta2> metas, WritableByteChannel channel) {
		jumbofile.transferTo((List)metas, channel);
	}
}
