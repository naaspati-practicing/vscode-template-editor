package sam.pkg.jsonfile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static sam.pkg.Utils.APP_DIR;
import static sam.pkg.Utils.SNIPPET_DIR;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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

import sam.collection.IndexedMap;
import sam.console.ANSI;
import sam.io.IOUtils;
import sam.io.infile.DataMeta2;
import sam.io.infile.TextInFile;
import sam.myutils.Checker;
import sam.nopkg.EnsureSingleton;
import sam.nopkg.Junk;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.JsonFile.Template;

public class CacheManager implements Closeable {
	private final EnsureSingleton singleton = new EnsureSingleton();

	public static final int KEYS = 0x777;
	
	private final JsonFile[] files;
	private final TextInFile jumbofile;
	private final AtomicInteger ids = new AtomicInteger(0); //FIXME set maxID 
	private final Path MYDIR = APP_DIR.resolve(CacheManager.class.getName());
	private final BitSet modifiedJsons = new BitSet();

	@SuppressWarnings({ "rawtypes" })
	public CacheManager() throws IOException {
		singleton.init();
		
		Path cfp = cacheFilePath();
		Map<Path, String> map;
		if(Files.notExists(cfp))
			map =  Collections.emptyMap();
		else {
			map = new HashMap<>();
			Files.lines(cfp)
			.forEach(s -> {
				int n = s.indexOf(' ');
				if(n < 0)
					return;
				map.put(SNIPPET_DIR.resolve(s.substring(n+1)), s.substring(0, n));
			});
		}
		
		ArrayList<JsonFile> files = new ArrayList<>();  
		
		Iterator<Path> itr = Files.walk(SNIPPET_DIR)
		.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"))
		.iterator();
		
		List<Path> newJsons = null;
		
		while (itr.hasNext()) {
			Path f = itr.next();
			String c = map.get(f);
			if(c == null) {
				if(newJsons == null)
					newJsons = new ArrayList<>();
				newJsons.add(f);
			} else {
				int n = c.indexOf('|');
				files.add(new JsonFile(Integer.parseInt(c.substring(0, n)), f, Long.parseLong(c.substring(n+1)), this));	
			}
		}
		if(Checker.isNotEmpty(newJsons)) {
			int namecount = SNIPPET_DIR.getNameCount();
			int ids = files.isEmpty() ? 0 : (files.stream().mapToInt(j -> j.id).max().orElse(0)+1);
			
			for (Path p : newJsons) {
				System.out.println("new Json: %SNIPPET_DIR%\\"+ p.subpath(namecount, p.getNameCount()));
				
				JsonFile j = new JsonFile(ids++, p, 0, this);
				setModified(j);
				files.add(j);
			}
		}
		
		this.files = files.toArray(new JsonFile[files.size()]);
		Arrays.sort(this.files, Comparator.comparingLong(j -> j.getLastModified() < 0 ? Long.MAX_VALUE : j.getLastModified()));
		
		Path jffile = MYDIR.resolve("jumbofile");
		//FIXME delete existing if data cache invalidated
		this.jumbofile = new TextInFile(jffile, true);
	}

	private Path cacheFilePath() {
		return MYDIR.resolve("cacheFilePath");
	}
	void setModified(JsonFile f) {
		modifiedJsons.set(f.id);
	}

	@Override
	public void close() throws IOException {
		if(!modifiedJsons.isEmpty()) {
			try(BufferedWriter w = Files.newBufferedWriter(cacheFilePath(), CREATE, APPEND)) {
				for (JsonFile f : files) {
					if(modifiedJsons.get(f.id)) {
						w.append(Integer.toString(f.id)).append('|')
						.append(Long.toString(f.getLastModified())).append(' ')
						.append(f.subpath().toString()).append('\n');
					}
				}	
			}
		}
		
		//FIXME save modified DateMeta2(s)
		
		/**
		 * JSON file close
	//TODO @Override
	public void close2() throws IOException {
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
		 */
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
	public void transfer(List<DataMeta2> metas, WritableByteChannel channel) throws IOException {
		jumbofile.transferTo((List)metas, channel);
	}
}
