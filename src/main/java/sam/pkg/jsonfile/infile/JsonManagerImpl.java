package sam.pkg.jsonfile.infile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.file.StandardOpenOption.*;
import static java.util.Arrays.asList;
import static java.util.Arrays.sort;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Comparator.comparingLong;
import static sam.io.IOUtils.compactOrClear;
import static sam.io.IOUtils.ensureCleared;
import static sam.myutils.Checker.anyMatch;
import static sam.myutils.Checker.assertTrue;
import static sam.myutils.Checker.isEmpty;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.myutils.Checker.isNotEmpty;
import static sam.myutils.Checker.isNotEmptyTrimmed;
import static sam.pkg.jsonfile.infile.Utils.*;
import static sam.pkg.jsonfile.infile.Utils.IB;
import static sam.pkg.jsonfile.infile.Utils.LB;
import static sam.pkg.jsonfile.infile.Utils.readArray;
import static sam.pkg.jsonfile.infile.Utils.readDataMetasByCount;
import static sam.pkg.jsonfile.infile.Utils.writeDataMetas;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
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
import sam.pkg.jsonfile.api.JsonFile;
import sam.pkg.jsonfile.api.JsonManager;
import sam.pkg.jsonfile.infile.JsonFileImpl.TemplateImpl;
import sam.pkg.jsonfile.infile.Utils.Provider;
import sam.pkg.jsonfile.infile.Utils.WriteMeta;
import sam.string.StringSplitIterator;
import sam.string.StringWriter2;

public class JsonManagerImpl implements Closeable, JsonManager, JsonLoader {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	public class JsonMeta {
		public static final int BYTES = IB + // id
				LB + // last-modified
				(LB + IB) * 2 // two DataMetas
				;
		
		public final JsonFileImpl jsonfile;
		private long lastmodified;
		private DataMeta names_meta, data_meta; // location of templates string, in smallfile
		private boolean templatesLoaded, modified;

		private JsonMeta(JsonFileImpl json) {
			this.jsonfile = json;
		}

		public long lastmodified() {
			return lastmodified;
		}

		public void put(ByteBuffer buf) {
			buf.putInt(jsonfile.id)
			.putLong(lastmodified);

			putMeta(names_meta, buf);
			putMeta(data_meta, buf);
		}
	}

	/*
	 * contains Json text
	 */
	private final TextInFile jumbofile;
	/*
	 * contains JsonFileImpl, templates
	 */
	private final TextInFile smallfile;

	final Path MYDIR;
	private final List<JsonFile> files;
	private final Path snippetDir;
	private final int SNIPPET_DIR_COUNT;
	private final JsonMetaManager jsonMetaManager;
	private final Map<MetaType, DataMeta> metaTypes = new EnumMap<>(MetaType.class);

	public JsonManagerImpl(Path snippetDir) throws Exception {
		this.snippetDir = Objects.requireNonNull(snippetDir);
		this.SNIPPET_DIR_COUNT = snippetDir.getNameCount();
		if(Files.isDirectory(snippetDir))
			throw new FileNotFoundException("snippet dir not found: "+snippetDir);

		this.MYDIR = AccessController.doPrivileged((PrivilegedExceptionAction<Path>)() -> {
			Path p = MyUtilsPath.TEMP_DIR.resolve(JsonManagerImpl.class.getName());
			Files.createDirectories(p);
			return p;
		});

		Path jumbofile_path = resolve("jumbofile");
		Path smallfile_path = resolve("smallfile");
		Path metatype_path = resolve(MetaType.class.getSimpleName());

		boolean fresh = false;

		if(Files.isDirectory(MYDIR)) {
			Path[] array = {jumbofile_path, smallfile_path, metatype_path};
			if(anyMatch(Files::notExists, array)) {
				for (Path p : array) 
					Files.deleteIfExists(p);

				fresh = true;
			}
		} else {
			fresh = true;
		}

		if(fresh) {
			Files.createDirectories(MYDIR);
			this.jsonMetaManager = new JsonMetaManager2();
			this.smallfile = new TextInFile(smallfile_path, true);
			this.jumbofile = new TextInFile(jumbofile_path, true);
		} else {
			this.smallfile = new TextInFile(smallfile_path, false);
			boolean success = false;
			MetaType[] array = MetaType.values();

			try(StringResources r = StringResources.get();
					FileChannel fc = FileChannel.open(metatype_path, StandardOpenOption.READ)) {

				readDataMetasByCount(fc, 0, array.length, r.buffer, (id, d) -> metaTypes.put(array[id], d));

				this.jsonMetaManager = new JsonMetaManager2(smallfile, r);
				this.jumbofile = new TextInFile(jumbofile_path, false);
				success = true;
			} finally {
				if(!success) {
					try {
						smallfile.close();
					} catch (Exception e) {
						logger.error("failed to close smallfile {}", smallfile_path, e);
					}
				}
			}
		}

		this.files = unmodifiableList(asList(jsonMetaManager.toFiles()));
	}



	private Path resolve(String s) {
		return MYDIR.resolve(s);
	}

	static final long POS = (MetaType.values().length + 2) * DATA_BYTES;

	public void close() throws IOException {
		Junk.notYetImplemented();

		jumbofile.close();

		//FIXME
		//check if were there any changes to save in metasPath()

		if(Junk.<Boolean>notYetImplemented()) { 
			try(StringResources r = StringResources.get();
					FileChannel fc = FileChannel.open(metasPath(), WRITE)) {
				//TODO
				WriteMeta w = jsonMetaManager.close(fc, r, POS);
				w = metasManager.close(r);

				smallfile.close();

				WriteMeta dm = writeDataMetas(0, fc, r.buffer, new Provider() {
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
				assertTrue(dm.size == MetaType.values().length * DATA_BYTES, () -> "dm.size("+dm.size+") == mtypes.length("+MetaType.values().length+") * DATA_BYTES("+DATA_BYTES+") = "+(MetaType.values().length * DATA_BYTES));
			}
		}
	}

	public List<TemplateImpl> getTemplates(JsonFileImpl json) throws IOException {
		DataMeta dm = jsonMetas[json.id].meta;

		if(dm == null)
			return null;
		else {
			if(dm.size == 0)
				return (dm.position == 0 ? null : new ArrayList<>());

			try(StringResources r = StringResources.get()) {
				Iterator<String> itr = readArray(dm, smallfile, r, '\t');
				Objects.requireNonNull(itr);

				ArrayList<TemplateImpl> result = new ArrayList<>();
				while(itr.hasNext()) 
					result.add(json.template(Integer.parseInt(itr.next()), itr.next(), itr.next()));

				return result;
			}
		}
	}

	public List<JsonFile> getFiles() {
		return files;
	}

	public DataMeta meta(TemplateImpl t) {
		return t == null ? null : metasManager.meta(t.id);
	}
	public StringBuilder loadText(TemplateImpl tm, StringResources r) throws IOException {
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

	public void write(TemplateImpl t, CharSequence sb, StringResources r) throws IOException {
		ensureCleared(r.buffer);
		DataMeta d = jumbofile.write(sb, r.encoder, r.buffer, REPORT, REPORT);
		metasManager.set(t.id, d);
	}

	public void transfer(List<DataMeta> metas, WritableByteChannel channel) throws IOException {
		jumbofile.transferTo(metas, channel);
	}

	public List<TemplateImpl> loadTemplates(JsonFileImpl json) throws IOException {
		List<TemplateImpl> list = metasManager.getTemplates((JsonFileImpl)json);
		return list == null ? parseJson(json) : list;
	}

	private List<TemplateImpl> parseJson(JsonFileImpl json) throws IOException {
		Path p = json.source();
		if(!Files.isRegularFile(p))
			throw new FileNotFoundException("file not found: "+p);

		List<TemplateImpl> templates = new ArrayList<>();

		try(StringResources r = StringResources.get()) {
			StringBuilder sb = r.sb();
			StringWriter2 sw = new StringWriter2(sb);

			//overrided to keep the order of keys;
			new JSONObject(new JSONTokener(Files.newBufferedReader(p, r.CHARSET))) {
				@Override
				public JSONObject put(String key, Object value) throws JSONException {
					super.put(key, value);
					if(has(key)) 
						templates.add(json.template(templates.size(), key, (JSONObject)value));
					return this;
				}
			};
			
			jsonMetaManager.jsonMetas(json).templatesLoaded = true;
			logger.info("loaded json({}): {}", ANSI.yellow(templates.size()), json);
		}
		return templates;
	}


	public Path subpath(Path source) {
		return source.subpath(SNIPPET_DIR_COUNT, source.getNameCount());
	}

	private class JsonMetaManager2 extends JsonMetaManager {
		public JsonMetaManager2() throws IOException {
			super();
		}
		public JsonMetaManager2(TextInFile smallfile, StringResources r) throws IOException {
			super(smallfile, r);
		}
		@Override
		protected Path snippetDir() {
			return snippetDir;
		}
		@Override
		protected JsonLoader loader() {
			return JsonManagerImpl.this;
		}
		@Override
		protected DataMeta metaTypes(MetaType key) {
			return metaTypes.get(key);
		}
		@Override
		protected void metaTypes(MetaType key, DataMeta value) {
			metaTypes.put(key, value);
		}
		@Override
		protected Path subpath(Path f) {
			return JsonManagerImpl.this.subpath(f);
		}
		@Override
		protected JsonMeta jsonMeta(JsonFileImpl json, long lastmodified, DataMeta textMeta, DataMeta datametaMeta) {
			JsonMeta m = new JsonMeta(json);
			m.lastmodified = lastmodified;
			m.names_meta = textMeta;
			m.data_meta = datametaMeta;
					
			return m;
		}
		
		
	}

}
