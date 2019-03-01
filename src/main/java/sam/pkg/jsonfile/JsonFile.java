package sam.pkg.jsonfile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static sam.myutils.Checker.isEmpty;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.myutils.Checker.isNotEmpty;
import static sam.pkg.Utils.SNIPPET_DIR;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import sam.collection.IndexedMap;
import sam.console.ANSI;
import sam.io.IOUtils;
import sam.io.infile.DataMeta2;
import sam.io.serilizers.StringIOUtils;
import sam.myutils.Checker;
import sam.myutils.MyUtilsPath;
import sam.nopkg.StringResources;
import sam.string.StringWriter2;
import sam.string.SubSequence;

public class JsonFile {
	private final CacheManager cmanager;
	private List<Template> data;
	private boolean modified, file_modified;
	private Throwable error;

	private long lastModified;
	private final Path subpath;
	private final Path source;

	private List<Template> newData;

	public static final String PREFIX = "prefix";
	public static final String BODY = "body";
	public static final String DESCRIPTION = "description";

	private int save_request;

	JsonFile(CacheManager cmanager, Path jsonSource, long lastModified) throws IOException {
		this.cmanager = cmanager;
		Checker.requireNonNull("jsonSource", jsonSource);

		this.source = jsonSource;
		this.subpath = MyUtilsPath.subpath(jsonSource, SNIPPET_DIR);
		this.lastModified = lastModified;

		if(Files.notExists(source))
			throw new IllegalArgumentException("file not found: "+source);

		this.file_modified = lastModified == 0 || lastModified != source.toFile().lastModified();

		/* TODO
		 * if(file_modified) 
			cacheFile.clear();
		 */
	}
	
	private void updateLastModified() {
		this.lastModified = source.toFile().lastModified();
	}
	public long getLastModified() {
		return lastModified;
	}

	private String name;
	@Override
	public String toString() {
		if(name != null) return name;
		return name = subpath.toString();
	}

	public Template add(String id, Template relativeTo) {
		if(newData == null)
			newData = new ArrayList<>();

		Template k = new Template(id);
		k.order = relativeTo.order;
		newData.add(k);
		return k;
	}
	public Stream<Template> getTemplates() {
		if(error != null) return null;
		if(data != null) {
			return newData == null ? 
					data.stream() : 
						Stream.concat(data.stream(), newData.stream());
		}

		DataMeta2 dm = file_modified  ? null : cmanager.meta(this, CacheManager.KEYS);

		if(dm == null) 
			init();
		else {
			boolean init = false;
			try(StringResources r = StringResources.get()) {
				StringBuilder sb = cmanager.loadText(dm, r);
				IndexedMap<DataMeta2> metas = cmanager.loadMetas(this, r, CacheManager.KEYS);

				if(data == null)
					data = new ArrayList<>();

				String[] array = new String[3];

				int n = 0;
				int start = 0;
				int i = 0;

				for (; i < sb.length(); i++) {
					char c = sb.charAt(i);

					if(c == '\t' || c == '\n') {
						array[n++] = sb.substring(start, i);
						if(c == '\n')
							data.add(new Template(array[1], array[2], metas.get(Integer.parseInt(array[0]))));

						start = i + 1;
					}
				}
				System.out.println("loaded-templates: " + this);
			} catch (IOException e) {
				System.out.println("failed to load templates: "+ this);
				e.printStackTrace(System.out);

				data = null;
				file_modified = true;
				init = true;
			}

			if(init)
				init();
		}

		for (int i = 0; i < data.size(); i++)  
			data.get(i).order = i;

		return data == null ? null : data.stream();
	}

	public boolean hasError() {
		return error != null;
	}
	private JSONObject getJson(Template template) throws IOException {
		init();

		if(template.dataMeta == null) 
			return null;

		try(StringResources r = StringResources.get()) {
			StringBuilder sb = cmanager.loadText(template.dataMeta, r);

			int start = 0;
			while(true) {
				if(sb.charAt(start) == '{')
					break;
				start++;
			}
			int end = sb.length() - 1;
			while(true) {
				if(sb.charAt(end) == '}') {
					end++;
					break;
				}
				end--;
			}
			return new JSONObject(new JSONTokener(new TempStringReader(new SubSequence(sb, start, end))));
		}
	}

	private class TempStringReader extends Reader {

		private CharSequence str;
		private int length;
		private int next = 0;
		private int mark = 0;

		public TempStringReader(CharSequence s) {
			super(":");
			this.str = s;
			this.length = s.length(); 
		}
		public int read() {
			if (next >= length)
				return -1;
			return str.charAt(next++);
		}

		public int read(char cbuf[], int off, int len) {
			if ((off < 0) || (off > cbuf.length) || (len < 0) ||
					((off + len) > cbuf.length) || ((off + len) < 0)) {
				throw new IndexOutOfBoundsException();
			} else if (len == 0) {
				return 0;
			}
			if (next >= length)
				return -1;
			int n = Math.min(length - next, len);
			if(str instanceof CharBuffer) {
				CharBuffer chars = ((CharBuffer)str);
				System.arraycopy(chars.array(), next, cbuf, off, n);
			} else if(str instanceof StringBuilder)
				((StringBuilder)str).getChars(next, next + n, cbuf, off);
			else {
				throw new RuntimeException(str.getClass().toString());
			}


			next += n;
			return n;
		}
		public long skip(long ns) {
			if (next >= length)
				return 0;
			// Bound skip by beginning and end of the source
			long n = Math.min(length - next, ns);
			n = Math.max(-next, n);
			next += n;
			return n;
		}

		public boolean ready() {
			return true;
		}

		public boolean markSupported() {
			return true;
		}

		public void mark(int readAheadLimit) {
			if (readAheadLimit < 0){
				throw new IllegalArgumentException("Read-ahead limit < 0");
			}
			mark = next;
		}

		public void reset() {
			next = mark;
		}

		public void close() {
			str = null;
		}
	}
	private boolean init;

	private void init() {
		if(init) return;
		init = true;

		try(StringResources r = StringResources.get()) {
			if(Files.exists(this.source)) {

				if(isNotEmpty(data))
					throw new IllegalStateException("data already initiilized");

				data = data != null ? data : new ArrayList<>();
				Path p = this.source;
				StringBuilder sb = r.sb();
				StringWriter2 sw = new StringWriter2(sb);

				//overrided to keep the order of keys;
				new JSONObject(new JSONTokener(Files.newBufferedReader(p, r.CHARSET))) {
					@Override
					public JSONObject put(String key, Object value) throws JSONException {
						super.put(key, value);
						if(has(key)) {
							Template t = new Template(key, (JSONObject)value);
							try {
								writeCache(t, sw, r);
							} catch (IOException e) {
								throw new JSONException(e);
							}
							data.add(t);
						}
						return this;
					}
				};
				System.out.println("loaded json("+ANSI.yellow(data.size())+"): "+this);
			} else {
				if(isEmpty(data))
					throw new IllegalStateException("data not loaded");
			}
		} catch (JSONException | IOException e) {
			error = e;
			data = null;
			return;
		}
	}

	public Throwable error() {
		return error;
	}
	private void remove(Template t) {
		if(newData == null || !newData.remove(t)) {
			data.remove(t);
			modified = true;
			System.out.println("REMOVED: \n"+t);
		}
	}
	private void save(Template t) {
		save_request++;

		if(t.dataMeta != null && newData != null && newData.remove(t)) {
			data.add(t.order, t);
			modified = true;
			t.dataMeta = null;
			System.out.println("SET MODIFIED: \n"+t);
		} else
			System.out.println("MODIFIED: \n"+t);
	}

	private static ByteBuffer START, END;

	public void save() throws IOException {
		if(save_request == 0) {
			System.out.println(ANSI.red("nothing to save: ")+this.source);
			return;
		}

		save_request = 0;

		try(FileChannel channel = FileChannel.open(this.source, WRITE, TRUNCATE_EXISTING);
				StringResources r = StringResources.get();
				) {

			if(START == null) {
				START = ByteBuffer.wrap("{\n".getBytes(r.CHARSET));
				END = ByteBuffer.wrap("\n}".getBytes(r.CHARSET));
			} 

			START.clear();
			channel.write(START);

			if(!data.isEmpty()) {
				if(data.size() > 1) {
					List<DataMeta2> metas = new ArrayList<>();
					for (int i = 0; i < data.size() - 1; i++){
						Template t = data.get(i); 
						if(t.dataMeta == null) 
							writeCache(t, null, r);

						metas.add(t.dataMeta);
					} 

					cmanager.transfer(metas, channel);	
				}

				StringBuilder sb = r.sb();
				sb.setLength(0);
				data.get(data.size() - 1).writeJson(new StringWriter2(sb));
				sb.append("\n}");

				StringIOUtils.write(b -> IOUtils.write(b, channel, false), sb, r.encoder, r.buffer, REPORT, REPORT);
				sb.setLength(0);
			} else {
				END.clear();
				channel.write(END);
			}
		}

		updateLastModified();
	}

	private void writeCache(Template t, StringWriter2 sw, StringResources r) throws IOException {
		if(sw == null)
			sw = new StringWriter2(r.sb());

		StringBuilder sb = sw.getBuilder();

		if(sb.length() != 0)
			throw new IOException();

		t.writeJson(sw);
		sw.append(",\n");
		t.dataMeta = cmanager.write(sb, r);
		sw.clear();
	}

	private static final JSONObject  TEMPLATE_JSON = new JSONObject();
	private static final StringWriter2 templatesw = new StringWriter2();

	public class Template implements Comparable<Template> {
		private DataMeta2 dataMeta;

		private transient int order;
		private final String id;
		private String prefix, body, description;

		//create new
		private Template(String id) {
			this.id = id;
			this.prefix = id;
			this.body = id;
		}

		// partial load
		private Template(String id, String prefix) {
			checkEmpty(id, "id");

			this.id = id;
			this.prefix = prefix;
		}
		private void checkEmpty(String value, String variable) {
			if(isEmptyTrimmed(value))
				throw new IllegalArgumentException("empty value for variable: "+variable+",  value: "+value);
		}
		private Template(String id, JSONObject json) {
			checkEmpty(id, "id");

			loaded = true;
			this.id = id;
			this.prefix = json.getString(PREFIX);
			this.body = json.getString(BODY);
			this.description = (String)json.opt(DESCRIPTION);
		}

		public Template(String id, String prefix, DataMeta2 dm) {
			this(id, prefix);
			this.dataMeta = dm;
		}

		private boolean loaded;
		private void load() {
			if(loaded) return;
			loaded = true;

			JSONObject json;
			try {
				json = getJson(this);
			} catch (IOException e) {
				throw new JSONException(e);
			}
			if(json == null) return;

			this.body = json.getString(BODY);
			this.description = json.optString(DESCRIPTION);

			System.out.println("Template loaded: "+id); 
		}

		private void writeJson(StringWriter2 w) {
			load();

			try {
				JSONObject json = TEMPLATE_JSON;

				json.put(PREFIX, prefix);
				json.put(BODY, body);
				json.put(DESCRIPTION, description);

				JSONObject.quote(id, w);
				w.append(':');
				json.write(w, 4, 2);
			} catch (IOException e) {
				throw new JSONException(e);
			}

		}

		public int order() {
			return order;
		}
		public String id() {
			return id;
		}
		public String prefix() {
			return prefix;
		}
		public String body() {
			load();
			return body;
		}
		public String description() {
			load();
			return description;
		}
		private String set(String old, String _new, String variableName) {
			load();

			if(variableName != DESCRIPTION)
				checkEmpty(_new, variableName);

			if(!Objects.equals(old, _new)) {
				dataMeta = null;
				return _new;
			}
			return old;
		}
		public void prefix(String prefix) {
			this.prefix = set(this.prefix, prefix, PREFIX);
		}
		public void body(String body) {
			this.body = set(this.body, body, BODY);
		}
		public void description(String description) {
			this.description = set(this.description, description, DESCRIPTION);
		}
		@Override
		public int hashCode() {
			return id.hashCode();
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Template other = (Template) obj;
			return Objects.equals(id, other.id);
		}

		@Override
		public String toString() {
			synchronized (templatesw) {
				StringWriter2 w = templatesw;
				w.clear();
				writeJson(w);
				String s = w.toString();
				w.clear();

				return s;	
			}
		}
		@Override
		public int compareTo(Template o) {
			int n = Integer.compare(this.order, o.order);
			if(n == 0)
				return newData.contains(o) ? 1 : -1;
			return n;
		}

		public void save() {
			JsonFile.this.save(this); 
		}
		public void remove() { 
			JsonFile.this.remove(this);
		}
	}
	public Path getSourcePath() {
		return this.source;
	}
	public boolean isModified() {
		return modified; //FIXME check if this object was modified during it was open
	}
}