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
import static sam.pkg.jsonfile.Utils.put;
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
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.JsonFile.Template;
import sam.string.StringWriter2;

public class JsonManager implements Closeable {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	private class JsonMeta {
		public static final int BYTES =
				IB + // id
				LB + // last-modified
				(LB + IB) * 2 // two DataMetas
				;

		final JsonFile jsonfile;
		long lastmodified;
		DataMeta templateStringMeta, templateDataMeta;
		boolean jsonLoaded, modified;

		public JsonMeta(JsonFile json) {
			this.jsonfile = json;
		}

		@Override
		public String toString() {
			return "JsonMeta [lastmodified=" + lastmodified + ", templateMeta=" + templateStringMeta + "]";
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
	private final JsonMeta[] metas;
	private final List<JsonFile> files;
	private final Path snippetDir;
	private final int SNIPPET_DIR_COUNT;
	private final BitSet templateMod = new BitSet();
	private final BitSet jsonFileMod = new BitSet();
	private int nextId;
	private final Map<MetaType, DataMeta> metaTypes = new EnumMap<>(MetaType.class);
	private final Path metas_path;

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

		Path jumbofile_path = MYDIR.resolve("jumbofile");
		Path smallfile_path = MYDIR.resolve("smallfile");
		metas_path = MYDIR.resolve("metas");

		if(Checker.anyMatch(Files::notExists, jumbofile_path, smallfile_path, metas_path)) {
			FilesUtilsIO.deleteDir(MYDIR);
			Files.createDirectories(MYDIR);
		}

		final boolean createIfNotExist = Files.notExists(smallfile_path);
		this.smallfile = new TextInFile(smallfile_path, createIfNotExist);
		boolean success = false;

		try(StringResources r = StringResources.get();
				FileChannel fc = FileChannel.open(metas_path, CREATE, READ, WRITE)) {


			if(fc.size() != 0) {
				MetaType[] mtypes = MetaType.values();
				readDataMetasByCount(fc, 0, mtypes.length, r.buffer, (id, dm) -> metaTypes.put(mtypes[id], dm));
			}

			Map<Integer, Path> idPathMap = jsonPathIdMap(snippetDir, smallfile, metaTypes.get(MetaType.JSON_FILE_PATH), r);
			Iterator<Path> json_files = jsonFiles(snippetDir);

			if(!idPathMap.isEmpty()) 
				idPathMap.values().removeIf(Checker::notExists);

			List<Path> newJsons = null;
			Map<Path, JsonMeta> result;

			if(idPathMap.isEmpty()) {
				result = emptyMap();
			} else {
				DataMeta dm = metaTypes.get(MetaType.JSON_FILES_META);

				if(dm == null) {
					result = emptyMap();
					idPathMap = emptyMap();
				} else {
					result = new HashMap<>();
					Map<Integer, Path> map = idPathMap; 

					smallfile.read(dm, r.buffer, b -> {
						while(b.remaining() >= JsonMeta.BYTES) {
							int id = b.getInt();
							Path source = map.remove(id);
							if(source == null) 
								logger.warn("no source found for id: {}", id);
							else {
								JsonMeta meta = new JsonMeta(new JsonFile(id, source, this));
								meta.lastmodified = b.getLong();
								meta.templateStringMeta = new DataMeta(b.getLong(), b.getInt());
								meta.templateDataMeta = new DataMeta(b.getLong(), b.getInt());

								result.put(source, meta);	
							}
						}
						compactOrClear(b);
					});
				}
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

			this.metas = new JsonMeta[result.isEmpty() ? 0 : result.values().stream().mapToInt(m -> m.jsonfile.id).max().getAsInt() + 1];
			result.forEach((s,m) -> this.metas[m.jsonfile.id] = m);
			JsonFile[] files = stream(this.metas).filter(Objects::nonNull).map(m -> m.jsonfile).toArray(JsonFile[]::new);
			sort(files, Comparator.comparingLong(j -> this.metas[j.id].lastmodified < 0 ? Long.MAX_VALUE : this.metas[j.id].lastmodified));
			this.files = unmodifiableList(asList(files));

			success = true;
		} finally {
			if(!success)
				smallfile.close();
		}

		this.jumbofile = new TextInFile(jumbofile_path, createIfNotExist);
	}

	@Override
	public void close() throws IOException {
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

		try(StringResources r = StringResources.get();
				FileChannel fc = FileChannel.open(metas_path, WRITE)) {

			MetaType[] mtypes = MetaType.values();
			DataMeta ZERO = new DataMeta(0, 0);
			ByteBuffer buf = r.buffer;

			int pos = 0;
			for (int i = 0; i < mtypes.length; i++) {
				if(buf.remaining() < DATA_BYTES)
					pos += IOUtils.write(buf, pos, fc, true);

				put(buf, i, metaTypes.getOrDefault(mtypes[i], ZERO));
			}

			IOUtils.write(buf, pos, fc, true);
		}


		smallfile.close();
		jumbofile.close();

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

	public StringBuilder loadText(DataMeta dm, StringResources r) throws IOException {
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
		t.dataMeta = d;
		templateMod.set(t.id);
	}

	public void transfer(List<DataMeta> metas, WritableByteChannel channel) throws IOException {
		jumbofile.transferTo(metas, channel);
	}
	public List<Template> loadTemplates(JsonFile json) throws IOException {
		DataMeta tsm = metas[json.id].templateStringMeta;
		DataMeta dm = metas[json.id].templateDataMeta;

		if(dm == null || tsm == null)
			return parseJson(json);
		else {
			if(tsm.size == 0)
				return (tsm.position == 0 ? parseJson(json) : new ArrayList<>());

			try(StringResources r = StringResources.get()) {
				Iterator<String> itr = readArray(tsm, smallfile, r, '\t');

				if(itr != null) {
					ArrayList<Template> result = new ArrayList<>();
					r.buffer.clear();

					smallfile.read(dm, r.buffer, b -> {
						while(b.remaining() > DATA_BYTES) {
							result.add(json.template(
									b.getInt(), 
									itr.next(), itr.next(), 
									new DataMeta(b.getLong(), b.getInt())));
						}
						compactOrClear(b);
					});
					return result;
				}
			}
			return parseJson(json);
		}
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
						Template t = json.template(nextId++, key, (JSONObject)value);

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

			metas[json.id].jsonLoaded = true;
			jsonFileMod.set(json.id);
			logger.info("loaded json({}): {}", ANSI.yellow(templates.size()), json);
		}

		return templates;
	}


	public Path subpath(Path source) {
		return source.subpath(SNIPPET_DIR_COUNT, source.getNameCount());
	}
}
