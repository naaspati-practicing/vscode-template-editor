package sam.pkg.jsonfile;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static sam.myutils.Checker.isEmpty;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.myutils.Checker.isNotEmpty;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import sam.console.ANSI;
import sam.myutils.MyUtilsException;
import sam.nopkg.Junk;
import sam.pkg.Utils;
import sam.string.StringWriter2;
import sam.string.SubSequence;

public class JsonFile implements Closeable {
	private static final LoadedFile loader = new LoadedFile();
	
	private List<Template> data;
	private boolean modified, file_modified;
	private Throwable error;
	private final CacheFile cache;

	private List<Template> newData;

	public static final String PREFIX = "prefix";
	public static final String BODY = "body";
	public static final String DESCRIPTION = "description";
	private int save_request;
	private static final Resource resource = Resource.getInstance();

	JsonFile(CacheFile cacheFile) throws IOException {
		if(Files.notExists(cacheFile.getSourcePath()))
			throw new IllegalArgumentException("file not found: "+cacheFile.getSourcePath());
		
		this.cache = cacheFile;
		this.file_modified = cacheFile.isSourceModified();

		if(file_modified) 
			cacheFile.clear();
	}
	
	private String name;
	@Override
	public String toString() {
		if(name != null) return name;
		return name = cache.subpath().toString();
	}

	public Template add(String id, Template relativeTo) {
		if(newData == null)
			newData = new ArrayList<>();

		Template k = new Template(id);
		k.order = relativeTo.order;
		newData.add(k);
		return k;
	}
	@SuppressWarnings("rawtypes")
	public Stream<Template> getTemplates() {
		if(error != null) return null;
		if(data != null) {
			return newData == null ? 
					data.stream() : 
						Stream.concat(data.stream(), newData.stream());
		}

		Path p = cache.keysPath();

		if(file_modified || !cache.exists()) 
			init();
		else {
			try(InputStream is = Files.newInputStream(p, READ);
					DataInputStream dos = new DataInputStream(is);
					) {
				int size = dos.readInt();

				if(data == null)
					data = new ArrayList<>(size+2);
				else if(data instanceof ArrayList)
					((ArrayList)data).ensureCapacity(size+2);

				for (int i = 0; i < size; i++) {
					Template t = new Template(dos.readUTF(), dos.readUTF());
					t.range = CacheMeta.of(dos.readInt(), dos.readInt());
					data.add(t);
				}
				System.out.println("loaded: "+p);
			} catch (IOException e) {
				data = null;
				e.printStackTrace();
				file_modified = true;
				init();
			}
		}

		for (int i = 0; i < data.size(); i++)  
			data.get(i).order = i;

		return data == null ? null : data.stream();
	}
	
	public boolean hasError() {
		return error != null;
	}
	private JSONObject getJson(Template template) {
		init();

		if(template.range == null) 
			return null;

		CharBuffer charBuffer = null;
		StringBuilder sink = null;
		
		ByteBuffer buf = MyUtilsException.noError(() -> loader.read(template.range, cache.cachePath()));

		try {
			CharsetDecoder decoder = resource.decoder();
			charBuffer = resource.charBuffer();

			while(true) {
				CoderResult result = decoder.decode(buf, charBuffer, true);

				if(result.isUnderflow()) {
					while(true) {
						result = decoder.flush(charBuffer);
						if(result.isUnderflow())
							break;
						else if(sink == null) 
							sink = resource.templateWriter().getBuilder();

						if(sink != null) {
							charBuffer.flip();
							sink.append(charBuffer);
							charBuffer.clear();
						}
					}
					break;
				}

				if(sink == null)
					sink = resource.templateWriter().getBuilder();

				if(sink != null) {
					charBuffer.flip();
					sink.append(charBuffer);
					charBuffer.clear();
				}
			}

			CharSequence chars;
			if(sink != null)
				chars = sink;
			else {
				charBuffer.flip();
				chars = charBuffer;
			}

			int start = 0;
			while(true) {
				if(chars.charAt(start) == '{')
					break;
				start++;
			}
			int end = chars.length() - 1;
			while(true) {
				if(chars.charAt(end) == '}') {
					end++;
					break;
				}
				end--;
			}
			return new JSONObject(new JSONTokener(new TempStringReader(new SubSequence(chars, start, end))));	
		} finally {
			if(charBuffer != null)
				charBuffer.clear();
			if(sink != null)
				sink.setLength(0);
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
	private boolean _init_loaded;

	private void init() {
		if(_init_loaded) return;
		_init_loaded = true;
		
		try {
			if(file_modified || !cache.exists()) {
				cache.clear();

				if(isNotEmpty(data))
					throw new IllegalStateException("data already initiilized");

				data = data != null ? data : new ArrayList<>();
				Path p = cache.getSourcePath();

				//overrided to keep the order of keys;
				new JSONObject(new JSONTokener(Files.newBufferedReader(p, resource.charset()))) {
					@Override
					public JSONObject put(String key, Object value) throws JSONException {
						super.put(key, value);
						if(has(key))
							data.add(new Template(key, (JSONObject)value));
						return this;
					}
				};
				System.out.println("loaded json("+ANSI.yellow(data.size())+"): "+this);
				_saveCache(p);
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
	private void _saveCache(Path p) throws IOException {
		if(data.isEmpty()) {
			cache.clear();
			return;
		}

		try(FileChannel os = FileChannel.open(p, CREATE, TRUNCATE_EXISTING, WRITE)) {
			StringWriter2 w = resource.templateWriter();
			Iterator<Template> iter = data.iterator();

			while (iter.hasNext()) {
				Template t = iter.next();

				long start = os.range();
				t.writeJson(w);
				if(iter.hasNext())
					w.append(",\n");

				write(w.getBuilder(), os);
				w.clear();
				t.range = new CacheMeta(start, os.range());

			}
		}
		System.out.println(ANSI.green("saved: ")+p);
		modified = true;
	}

	private void write(CharSequence s, FileChannel os) throws IOException {
		ByteBuffer buffer = resource.buffer(-1);
		CharsetEncoder encoder = resource.encoder();
		CharBuffer chars = CharBuffer.wrap(s);

		while(true) {
			CoderResult c = encoder.encode(chars, buffer, true);
			write0(buffer, os);

			if(!chars.hasRemaining()) {
				while(true) {
					c = encoder.flush(buffer);
					write0(buffer, os);
					if(c.isUnderflow()) break;
				}
				break;
			}
		}
		buffer.clear();
	}
	private void write0(ByteBuffer buffer, FileChannel os) throws IOException {
		buffer.flip();

		while(buffer.hasRemaining())
			os.write(buffer);
		buffer.clear();
	}
	
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

					CacheMeta pos = t.range == null ? CacheMeta.DEFAULT : t.range;
					dos.writeInt(pos.position());
					dos.writeInt(pos.size());
				}
				//FIXME append instead of truncate
			}
			System.out.println(ANSI.green("saved: ")+p);
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

		if(t.range != null && newData != null && newData.remove(t)) {
			data.add(t.order, t);
			modified = true;
			t.range = null;
			System.out.println("SET MODIFIED: \n"+t);
		} else
			System.out.println("MODIFIED: \n"+t);
	}
	
	private static final byte[] START = "{\n".getBytes(resource.charset());
	private static final byte[] END = "\n}".getBytes(resource.charset());
	
	public void save() throws IOException {
		if(save_request == 0) {
			System.out.println(ANSI.red("nothing to save: ")+cache.getSourcePath());
			return;
		}

		save_request = 0;
		ByteBuffer buffer = resource.buffer(-1);

		try(FileChannel channel = FileChannel.open(cache.getSourcePath(), CREATE, WRITE, TRUNCATE_EXISTING)) {
			buffer.put(START);

			if(!data.isEmpty())
				_save(channel, buffer);

			if(buffer.remaining() < END.length)
				Utils.write(buffer, channel);
			
			buffer.put(END);
			Utils.write(buffer, channel);
		} finally {
			buffer.clear();
		}
		cache.updateLastModified();
	}
	
	private void _save(FileChannel channel, ByteBuffer buffer) {
		CharsetDecoder decoder = resource.decoder();
		StringWriter2 writer = resource.templateWriter();
		
		if(data.size() > 1) {
			for (int i = 0; i < data.size() - 1; i++) {
				Template t = data.get(i);
				writeJson(t, writer, decoder);
			}
		}
	}

	private void writeJson(Template t, StringWriter2 writer, CharsetDecoder decoder) {
		if(t.range != null && t.range != CacheMeta.DEFAULT) {
			//FIXME
		}
			
	}

	private static final JSONObject  TEMPLATE_JSON = new JSONObject();

	public class Template implements Comparable<Template> {
		public CacheMeta range;
		private String jsonString;

		private transient int order;
		private final String id;
		private String prefix, body, description;

		//create new
		private Template(String id) {
			this.id = id;
			this.prefix = id;
			this.body = id;
		}
		public boolean isModified() {
			return range == null;
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

		private boolean loaded;
		private void load() {
			if(loaded) return;
			loaded = true;

			JSONObject json = getJson(this);
			if(json == null) return;

			this.body = json.getString(BODY);
			this.description = json.optString(DESCRIPTION);

			System.out.println("Template loaded: "+id); 
		}

		private void writeJson(StringWriter2 w) {
			load();

			if(jsonString != null) {
				w.append(jsonString);
				return;
			}

			JSONObject json = TEMPLATE_JSON;

			json.put(PREFIX, prefix);
			json.put(BODY, body);
			json.put(DESCRIPTION, description);

			try {
				JSONObject.quote(id, w);
			} catch (IOException e) {
				new JSONException(e);
			}
			w.append(':');
			json.write(w, 4, 2);
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
				jsonString = null;
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
			if(jsonString != null) return jsonString;

			StringWriter2 w = resource.templateWriter();
			w.clear();
			writeJson(w);
			jsonString = w.toString();
			w.clear();

			return jsonString;
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
		return cache.getSourcePath();
	}
	public CacheFile getCacheFile() {
		return cache;
	}

	public boolean isModified() {
		return modified; //FIXME check if this object was modified during it was open
	}
}