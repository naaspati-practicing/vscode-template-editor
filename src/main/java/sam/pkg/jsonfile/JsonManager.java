package sam.pkg.jsonfile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Arrays.asList;
import static java.util.Arrays.sort;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static sam.io.IOUtils.compactOrClear;
import static sam.io.IOUtils.ensureCleared;
import static sam.pkg.jsonfile.Utils.DATA_BYTES;
import static sam.pkg.jsonfile.Utils.IB;
import static sam.pkg.jsonfile.Utils.LB;
import static sam.pkg.jsonfile.Utils.jsonFiles;
import static sam.pkg.jsonfile.Utils.jsonPathIdMap;
import static sam.pkg.jsonfile.Utils.readArray;
import static sam.pkg.jsonfile.Utils.readDataMetasByCount;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.console.ANSI;
import sam.io.IOUtils;
import sam.io.fileutils.FilesUtilsIO;
import sam.io.infile.DataMeta;
import sam.io.infile.TextInFile;
import sam.myutils.Checker;
import sam.myutils.MyUtilsPath;
import sam.nopkg.Junk;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.JsonFile.Template;
import sam.pkg.jsonfile.Utils.Provider;
import sam.pkg.jsonfile.Utils.WriteMeta;
import sam.string.StringWriter2;

public class JsonManager implements Closeable {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	private class JsonMeta {
		final JsonFile jsonfile;
		long lastmodified;
		DataMeta meta; // location of templates string, in smallfile
		boolean templatesLoaded, modified;

		public JsonMeta(JsonFile json) {
			this.jsonfile = json;
		}
	}

	private final boolean newFound;

	/*
	 * contains Json text
	 */
	private final TextInFile jumbofile;
	/*
	 * contains JsonFile, templates
	 */
	private final TextInFile smallfile;

	final Path MYDIR;
	private final JsonMeta[] jsonMetas;
	private final List<JsonFile> files;
	private final Path snippetDir;
	private final int SNIPPET_DIR_COUNT;
	private final MetasManager metasManager;
	private final Map<MetaType, DataMeta> metaTypes = new EnumMap<>(MetaType.class);

	public JsonManager(Path snippetDir) throws Exception {
		this.snippetDir = Objects.requireNonNull(snippetDir);
		this.SNIPPET_DIR_COUNT = snippetDir.getNameCount();
		if(Files.isDirectory(snippetDir))
			throw new FileNotFoundException("snippet dir not found: "+snippetDir);

		this.MYDIR = AccessController.doPrivileged((PrivilegedExceptionAction<Path>)() -> {
			Path p = MyUtilsPath.TEMP_DIR.resolve(JsonManager.class.getName());
			Files.createDirectories(p);
			return p;
		});

		Path jumbofile_path = resolve("jumbofile");
		Path smallfile_path = resolve("smallfile");
		Path metas_path = metasPath();

		if(Checker.anyMatch(Files::notExists, jumbofile_path, smallfile_path, metas_path)) {
			FilesUtilsIO.deleteDir(MYDIR);
			Files.createDirectories(MYDIR);
		}

		final boolean createIfNotExist = Files.notExists(smallfile_path);
		this.smallfile = new TextInFile(smallfile_path, createIfNotExist);
		boolean success = false;

		try(StringResources r = StringResources.get();
				FileChannel metas = FileChannel.open(metas_path, CREATE, READ, WRITE)) {

			if(metas.size() != 0) {
				MetaType[] mtypes = MetaType.values();
				readDataMetasByCount(metas, 0, mtypes.length, r.buffer, (id, dm) -> metaTypes.put(mtypes[id], dm));
			}

			this.metasManager = new MetasManager(metas, r.buffer);
			r.buffer.clear();
			Map<Integer, Path> idPathMap = jsonPathIdMap(snippetDir, smallfile, metaTypes.get(MetaType.JSON_FILE_PATH), r);
			Iterator<Path> json_files = jsonFiles(snippetDir);

			if(!idPathMap.isEmpty()) 
				idPathMap.values().removeIf(Checker::notExists);

			List<Path> newJsons = null;
			Map<Path, JsonMeta> result = new JsonMetaManager().read(r.buffer, idPathMap);

			if(Checker.isEmpty(result)) {
				result = emptyMap();
				idPathMap = emptyMap();
			}

			while (json_files.hasNext()) {
				Path f = json_files.next();
				JsonMeta meta = result.get(f);

				if(meta != null && meta.lastmodified != f.toFile().lastModified()) {
					logger.warn("reset JsonFile: lastmodified({} -> {}), path: \"{}\"", meta.lastmodified, f.toFile().lastModified(), subpath(f));
					meta = null;
					result.remove(f);
				}

				if(meta == null) {
					if(newJsons == null)
						newJsons = new ArrayList<>();
					newJsons.add(f);
				}
			}

			this.newFound = Checker.isNotEmpty(newJsons);

			if(newFound) {
				BitSet setIds = new BitSet();
				result.forEach((p, m) -> setIds.set(m.jsonfile.id));
				int nextId = 0;

				for (Path p : newJsons) {
					int id = 0;
					while(setIds.get(id = nextId++)) {}

					String subpath = subpath(p).toString();

					result.put(p, new JsonMeta(new JsonFile(id, p, this)));
					logger.info("new JsonFile id: {}: %SNIPPET_DIR%\\{}", id, subpath);
				}
			}

			this.jsonMetas = new JsonMeta[result.isEmpty() ? 0 : result.values().stream().mapToInt(m -> m.jsonfile.id).max().getAsInt() + 1];
			result.forEach((s,m) -> this.jsonMetas[m.jsonfile.id] = m);
			JsonFile[] files = stream(this.jsonMetas).filter(Objects::nonNull).map(m -> m.jsonfile).toArray(JsonFile[]::new);
			sort(files, Comparator.comparingLong(j -> this.jsonMetas[j.id].lastmodified < 0 ? Long.MAX_VALUE : this.jsonMetas[j.id].lastmodified));
			this.files = unmodifiableList(asList(files));

			success = true;
		} finally {
			if(!success)
				smallfile.close();
		}

		this.jumbofile = new TextInFile(jumbofile_path, createIfNotExist);
	}

	private Path resolve(String s) {
		return MYDIR.resolve(s);
	}
	private Path metasPath() {
		return resolve("metas");
	}

	static final int JSON_META_BYTES =
			IB + // id
			LB + // last-modified
			(LB + IB) * 2 // two DataMetas
			;
	static final long POS = (MetaType.values().length + 2) * DATA_BYTES;

	private class JsonMetaManager {
		WriteMeta write(FileChannel fc, ByteBuffer buf) throws IOException {
			IOUtils.ensureCleared(buf);
			WriteMeta w = new WriteMeta(POS);

			buf.clear();

			for (JsonMeta m : jsonMetas) {
				if(buf.remaining() < JSON_META_BYTES)
					w.write(buf, fc, true);

				buf.putInt(m.jsonfile.id)
				.putLong(m.lastmodified)
				.putLong(m.meta == null ? 0 : m.meta.position)
				.putInt(m.meta == null ? 0 : m.meta.size);
			}

			w.write(buf, fc, true);
			Checker.assertTrue(w.size == jsonMetas.length * JSON_META_BYTES, () -> String.format("w.size (%s) == jsonMetas.length (%s) * JSON_META_BYTES (%s) = %s", w.size, jsonMetas.length, JSON_META_BYTES, jsonMetas.length * JSON_META_BYTES));
			
			metaTypes.put(MetaType.JSON_META_MANAGER, new DataMeta(w.init_pos, w.size));
			return w;
		}

		public Map<Path, JsonMeta> read(ByteBuffer buffer, Map<Integer, Path> idPathMap) throws IOException {
			DataMeta dm;

			if(Checker.isEmpty(idPathMap)) {
				return emptyMap();
			} else {
				dm = metaTypes.get(MetaType.JSON_META_MANAGER);
				if(dm == null)
					return emptyMap();
			}

			IOUtils.ensureCleared(buffer);
			Map<Path, JsonMeta> result = new HashMap<>(); 

			smallfile.read(dm, buffer, b -> {
				while(b.remaining() >= JSON_META_BYTES) {
					int id = b.getInt();
					Path source = idPathMap.remove(id);
					if(source == null) 
						logger.warn("no source found for id: {}", id);
					else {
						JsonMeta meta = new JsonMeta(new JsonFile(id, source, JsonManager.this));
						meta.lastmodified = b.getLong();
						meta.meta = new DataMeta(b.getLong(), b.getInt());
						result.put(source, meta);	
					}
				}
				compactOrClear(b);
			});
			return result;
		}
	}

	private class MetasManager {
		private final DataMeta[] metas;
		private DataMeta[] metas_new;
		private int _nextId = 0;
		private int _mod = 0;
		private int META_BYTES = IB + IB; // non-null count, max_non_id
		
		public MetasManager(FileChannel fc, ByteBuffer buffer) throws IOException {
			DataMeta dm = metaTypes.get(MetaType.METAS_MANAGER);
			if(dm == null || dm.size == 0) {
				metas = new DataMeta[0];
				metas_new = new DataMeta[0];
			} else {
				ensureCleared(buffer);
				buffer.limit(META_BYTES);
				
				long pos = dm.position;
				
				if(fc.read(buffer, pos) < META_BYTES)
					throw new IOException();
				
				pos += META_BYTES;
				buffer.flip();
				int count = buffer.getInt();
				int maxId = buffer.getInt();
				
				Checker.assertTrue(maxId < 10000);
				
				buffer.clear();
				this.metas = new DataMeta[maxId + 1];
				
				if(count != 0) {
					int c[] = {0};
					
					Utils.readDataMetasBySize(fc, pos, dm.size - META_BYTES, buffer, (id, d) -> {
						c[0]++;
						this.metas[id] = d;
					});
					Checker.assertTrue(c[0] == count);
				}
			}
		}

		public List<Template> getTemplates(JsonFile json) throws IOException {
			DataMeta dm = jsonMetas[json.id].meta;

			if(dm == null)
				return null;
			else {
				if(dm.size == 0)
					return (dm.position == 0 ? null : new ArrayList<>());

				try(StringResources r = StringResources.get()) {
					Iterator<String> itr = readArray(dm, smallfile, r, '\t');
					Objects.requireNonNull(itr);

					ArrayList<Template> result = new ArrayList<>();
					while(itr.hasNext()) 
						result.add(json.template(Integer.parseInt(itr.next()), itr.next(), itr.next()));

					return result;
				}
			}
		}

		public void set(int id, DataMeta d) {
			_mod++;

			if(id < metas.length)
				metas[id] = d;
			else {
				if(id - metas.length >= metas_new.length) {
					DataMeta[] ds = new DataMeta[Math.max(metas_new.length * metas_new.length/4, 20)];
					System.arraycopy(metas_new, 0, ds, 0, metas_new.length);
					
					logger.debug("templates_new.length({} -> {})", metas_new.length, ds.length);
					metas_new = ds;
				}
				
				metas_new[id - metas.length] = d;
			}
			
			logger.debug("set DataMeta -> id: {}, meta: {}", id, d);
		}

		private DataMeta meta(int id) {
			if(id < metas.length)
				return metas[id];
			else if(id - metas.length < metas_new.length)
				return metas_new[id - metas.length];
			else
				return null;
		}

		public int nextId() {
			while(meta(_nextId++) != null) {}
			return _nextId - 1; 
		}

		public WriteMeta close(FileChannel fc, ByteBuffer buf, long minPos) throws IOException {
			WriteMeta w = new WriteMeta(minPos + 50);
			ensureCleared(buf);
			int maxId = 0;
			
			if(metas_new.length == 0)
				maxId = metas.length - 1;
			else {
				for (int i = metas_new.length; i >= 0; i--) {
					if(metas_new[i] != null) {
						maxId = i;
						break;
					}
				}
			}
			
			if(maxId < metas.length)
				metas_new = new DataMeta[0];
			
			int count = count(metas) + count(metas_new);
			buf.limit(META_BYTES);
			
			buf
			.putInt(count)
			.putInt(maxId < 0 ? 0 : maxId);
			
			w.write(buf, fc, true);
			
			int mi = maxId;
			WriteMeta w2 = Utils.writeDataMetas(w.pos, fc, buf, new Provider() {
				int id = 0;
				DataMeta dm;
				@Override
				public boolean next() throws IOException {
					if(id > mi)
						return false;
					
					while((dm = meta(id++)) == null && id <= mi) {}
					return dm != null;
				}
				@Override
				public int id() {
					return id - 1;
				}
				@Override
				public DataMeta dataMeta() {
					return dm;
				}
			});
			
			WriteMeta r = new WriteMeta(w.init_pos);
			r.pos = w2.pos;
			r.size = w2.size + w.size;
			
			Checker.assertTrue(r.size == count * DATA_BYTES );
			metaTypes.put(MetaType.METAS_MANAGER, new DataMeta(w.init_pos, w.size));
			return r;
		}

		private int count(DataMeta[] metas) {
			if(metas.length == 0)
				return 0;
			int n = 0;
			for (DataMeta d : metas) {
				if(d != null)
					n++;
			}
			return n;
		}
	}

	@Override
	public void close() throws IOException {
		Junk.notYetImplemented();

		/* TODO
		 * if(newFound || Checker.anyMatch(JsonFile::isSaved, files)) {
			metas = new JsonMeta[stream(files).mapToInt(f -> f == null ? 0 : f.id).max().orElse(0) + 1];

			for (JsonFile f : files) 
				metas[f.id] = new JsonMeta(f.lastModified(), f.templateMeta);
		}

		try(StringResources r = StringResources.get()) {
			dataMetas.close(metas, r.buffer);
		}
		 */

		smallfile.close();
		jumbofile.close();

		final DataMeta ZERO = new DataMeta(0, 0);

		//FIXME
		//check if were there any changes to save in metasPath()
		if(Junk.<Boolean>notYetImplemented()) { 
			try(StringResources r = StringResources.get();
					FileChannel fc = FileChannel.open(metasPath(), WRITE)) {

				ByteBuffer buf = r.buffer;

				WriteMeta w = new JsonMetaManager().write(fc, buf);
				w = metasManager.close(fc, buf, w.pos);

				WriteMeta dm = Utils.writeDataMetas(0, fc, r.buffer, new Provider() {
					int n = 0;
					MetaType t;
					MetaType[] array = MetaType.values();

					@Override
					public boolean next() throws IOException {
						int n = this.n++;
						if(n >= array.length)
							return false;

						t = array[n];
						return true;
					}
					@Override
					public int id() {
						return t.ordinal();
					}
					@Override
					public DataMeta dataMeta() {
						return metaTypes.getOrDefault(t, ZERO);
					}
				});

				Checker.assertTrue(dm.size == MetaType.values().length * DATA_BYTES, () -> "dm.size("+dm.size+") == mtypes.length("+MetaType.values().length+") * DATA_BYTES("+DATA_BYTES+") = "+(MetaType.values().length * DATA_BYTES));

			}
		}



		/*
		 * if(Checker.anyMatch(f -> f.isSaved(), files)) {
			try(BufferedWriter w = Files.newBufferedWriter(cacheFilePath(), CREATE, APPEND)) {
				for (JsonFile f : files) {
					if(f.isSaved()) {
						w.append(Integer.toString(f.id)).append('|')
						.append(Long.toString(lastmodified[f.id])).append(' ')
						.append(f.subpath().toString()).append('\n');
					}
				}	
			}
		}
		 */

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
		return files;
	}

	public DataMeta meta(Template t) {
		return t == null ? null : metasManager.meta(t.id);
	}
	public StringBuilder loadText(Template tm, StringResources r) throws IOException {
		return loadText(meta(tm), r);
	}
	private  StringBuilder loadText(DataMeta dm, StringResources r) throws IOException {
		if(dm == null)
			return null;

		StringBuilder sb = r.sb();
		if(sb.length() != 0)
			throw new IOException("sb.length("+sb.length()+") != 0");

		ensureCleared(r.buffer);
		ensureCleared(r.chars);

		jumbofile.readText(dm, r.buffer, r.chars, r.decoder, sb, REPORT, REPORT);

		r.chars.clear();
		r.buffer.clear();
		return sb;
	}

	public void write(Template t, CharSequence sb, StringResources r) throws IOException {
		ensureCleared(r.buffer);
		DataMeta d = jumbofile.write(sb, r.encoder, r.buffer, REPORT, REPORT);
		metasManager.set(t.id, d);
	}

	public void transfer(List<DataMeta> metas, WritableByteChannel channel) throws IOException {
		jumbofile.transferTo(metas, channel);
	}
	public List<Template> loadTemplates(JsonFile json) throws IOException {
		List<Template> list = metasManager.getTemplates(json);
		return list == null ? parseJson(json) : list;
	}

	private List<Template> parseJson(JsonFile json) throws IOException {
		Path p = json.source;
		if(!Files.isRegularFile(p))
			throw new FileNotFoundException("file not found: "+p);

		List<Template> templates = new ArrayList<>();

		try(StringResources r = StringResources.get()) {
			StringBuilder sb = r.sb();
			StringWriter2 sw = new StringWriter2(sb);

			//overrided to keep the order of keys;
			new JSONObject(new JSONTokener(Files.newBufferedReader(p, r.CHARSET))) {
				@Override
				public JSONObject put(String key, Object value) throws JSONException {
					super.put(key, value);
					if(has(key)) {
						Template t = json.template(metasManager.nextId(), key, (JSONObject)value);

						try {
							json.writeCache(t, sw, r);
						} catch (IOException e) {
							throw new JSONException(e);
						}
						templates.add(t);
					}
					return this;
				}
			};

			jsonMetas[json.id].templatesLoaded = true;
			logger.info("loaded json({}): {}", ANSI.yellow(templates.size()), json);
		}

		return templates;
	}


	public Path subpath(Path source) {
		return source.subpath(SNIPPET_DIR_COUNT, source.getNameCount());
	}
}
