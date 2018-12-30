package sam.pkg.jsonfile;
import static java.nio.charset.CodingErrorAction.REPORT;
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
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
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
import sam.pkg.Main;
import sam.string.StringWriter2;
import sam.string.SubSequence;

public class JsonFile implements Closeable {
	// private static final Logger LOGGER = MyLoggerFactory.logger(JsonFile.class);

	public final Path jsonFilePath;
	private long lastModified;
	public final int id;
	private boolean modified, file_modified;
	private boolean fileExists;
	private Throwable error;

	private List<Template> data;
	private List<Template> newData;

	private FileChannel _indexed_json;

	public static final String PREFIX = "prefix";
	public static final String BODY = "body";
	public static final String DESCRIPTION = "description";
	private int save_request;

	private static final Charset CHARSET = StandardCharsets.UTF_8;
	private static final ByteBuffer _BUFFER = ByteBuffer.allocate(1024*8);
	private static final CharBuffer _CHARS = CharBuffer.allocate(1024);
	private CharsetEncoder ENCODER;
	private CharsetDecoder DECODER;

	private static final StringWriter2 _TEMPLATE_WRITER = new StringWriter2();
	private static final JSONObject  TEMPLATE_JSON = new JSONObject();

	private CharBuffer charBuffer() {
		if(_CHARS.remaining() != _CHARS.capacity())
			throw new IllegalStateException(String.valueOf(_CHARS.remaining()));
		return _CHARS;
	}
	private StringWriter2 templateWriter() {
		if(_TEMPLATE_WRITER.getBuilder().length() != 0)
			throw new IllegalStateException(String.valueOf(_TEMPLATE_WRITER.getBuilder().length()));
		return _TEMPLATE_WRITER;
	}
	private ByteBuffer buffer() {
		if(_BUFFER.remaining() != _BUFFER.capacity())
			throw new IllegalStateException(String.valueOf(_BUFFER.remaining()));
		return _BUFFER;
	}

	JsonFile(int id, Path jsonFilePath, long oldLastModified) throws IOException {
		this.jsonFilePath = jsonFilePath;
		this.id = id;

		this.lastModified = oldLastModified;
		this.fileExists = Files.exists(jsonFilePath);
		this.file_modified = fileExists && oldLastModified != jsonFilePath.toFile().lastModified();

		if(!fileExists || file_modified) {
			delete(keysPath());
			delete(cachePath());
		}

	}
	public boolean isFileExists() {
		return fileExists;
	}
	private void delete(Path p) throws IOException {
		if(Files.deleteIfExists(p))
			System.out.println("DELETED: "+p);
			//LOGGER.fine(() -> "DELETED: "+p);
	}
	public long lastModified() {
		return lastModified;
	}
	private String name;
	@Override
	public String toString() {
		if(name != null) return name;
		return name = Main.relativeToSnippedDir(jsonFilePath);
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

		Path p = keysPath();

		if(file_modified || Files.notExists(p)) 
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
					Position pos = new Position(dos.readLong(), dos.readLong());
					if(pos.start != -1 && pos.end != -1)
						t.position = pos;

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
	private Path keysPath() {
		return Main.CACHE_DIR.resolve(id+".keys");
	}
	public boolean hasError() {
		return error != null;
	}
	private JSONObject getJson(Template template) {
		init();

		if(template.position == null) 
			return null;

		Position p = template.position;

		ByteBuffer buffer = null;
		CharBuffer charBuffer = null;
		StringBuilder sink = null;

		try {
			buffer = buffer();
			int len = p.length();

			if(buffer.capacity() < len) {
				buffer = ByteBuffer.allocate(len);
				System.out.println("new buffer created of length: "+len);
			}

			buffer.position(buffer.capacity() - len);
			try {
				indexed_json().read(buffer, p.start);
			} catch (IOException e1) {
				throw new RuntimeException(e1);
			}

			if(buffer.limit() != buffer.capacity())
				throw new IllegalStateException();

			buffer.position(buffer.capacity() - len);

			if(DECODER == null)
				DECODER = CHARSET.newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT);
			else
				DECODER.reset();

			charBuffer = charBuffer();

			while(true) {
				CoderResult result = DECODER.decode(buffer, charBuffer, true);

				if(result.isUnderflow()) {
					while(true) {
						result = DECODER.flush(charBuffer);
						if(result.isUnderflow())
							break;
						else if(sink == null) 
							sink = templateWriter().getBuilder();

						if(sink != null) {
							charBuffer.flip();
							sink.append(charBuffer);
							charBuffer.clear();
						}
					}
					break;
				}

				if(sink == null)
					sink = templateWriter().getBuilder();

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
			if(buffer != null)
				buffer.clear();
			if(charBuffer != null)
				charBuffer.clear();
			if(sink != null)
				sink.setLength(0);
		}
	}

	private FileChannel indexed_json() throws IOException {
		if(_indexed_json == null)
			_indexed_json = FileChannel.open(cachePath(), READ);
		return _indexed_json;
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

		Path p = cachePath(); 
		try {
			if(file_modified || Files.notExists(p)) {
				Files.deleteIfExists(p);

				if(isNotEmpty(data))
					throw new IllegalStateException("data already initiilized");

				data = data != null ? data : new ArrayList<>();

				//overrided to keep the order of keys;
				new JSONObject(new JSONTokener(Files.newBufferedReader(jsonFilePath, CHARSET))) {
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
			Files.deleteIfExists(p);
			return;
		}

		try(FileChannel os = FileChannel.open(p, CREATE, TRUNCATE_EXISTING, WRITE)) {
			StringWriter2 w = templateWriter();
			Iterator<Template> iter = data.iterator();

			while (iter.hasNext()) {
				Template t = iter.next();

				long start = os.position();
				t.writeJson(w);
				if(iter.hasNext())
					w.append(",\n");

				write(w.getBuilder(), os);
				w.clear();
				t.position = new Position(start, os.position());

			}
		}
		System.out.println(ANSI.green("saved: ")+p);
		modified = true;
	}

	private void write(CharSequence s, FileChannel os) throws IOException {
		ByteBuffer buffer = buffer();

		if(ENCODER == null)
			ENCODER = CHARSET.newEncoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT);
		else
			ENCODER.reset();

		CharBuffer chars = CharBuffer.wrap(s);

		while(true) {
			CoderResult c = ENCODER.encode(chars, buffer, true);
			write0(buffer, os);

			if(!chars.hasRemaining()) {
				while(true) {
					c = ENCODER.flush(buffer);
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

	private Path cachePath() {
		return Main.CACHE_DIR.resolve(id+".data");
	}
	private static class Position {
		final long start, end;

		public Position(long start, long end) {
			this.start = start;
			this.end = end;
		}

		public int length() {
			long l = end - start;
			if(l > Integer.MAX_VALUE)
				throw new IllegalStateException();
			return (int) l;
		}

		@Override
		public String toString() {
			return "Position [start=" + start + ", end=" + end + ", length=" + length() + "]";
		}
	}
	@Override
	public void close() throws IOException {
		if(_indexed_json != null)
			_indexed_json.close();

		if(hasError() || data == null) return;

		Position DEFAULT = new Position(-1, -1);

		if(modified) {
			Path p = keysPath();
			try(OutputStream is = Files.newOutputStream(p, CREATE,TRUNCATE_EXISTING);
					DataOutputStream dos = new DataOutputStream(is);
					) {
				dos.writeInt(data.size());

				for (Template t : data) {
					dos.writeUTF(t.id);
					dos.writeUTF(t.prefix);

					Position pos = t.position == null ? DEFAULT : t.position;
					dos.writeLong(pos.start);
					dos.writeLong(pos.end);
				}
			}
			System.out.println(ANSI.green("saved: ")+p);
		}
	}
	public Throwable error() {
		return error;
	}
	private static final ByteBuffer START = ByteBuffer.wrap("{\n".getBytes(CHARSET));
	private static final ByteBuffer END = ByteBuffer.wrap("\n}".getBytes(CHARSET));

	public void save() throws IOException {
		if(save_request == 0) {
			System.out.println(ANSI.red("nothing to save: ")+jsonFilePath);
			//LOGGER.warning("nothing to save: "+jsonFilePath);
			return;
		}

		save_request = 0;

		try(FileChannel channel = FileChannel.open(jsonFilePath, CREATE, WRITE, TRUNCATE_EXISTING)) {
			START.clear();
			channel.write(START);

			if(!data.isEmpty())
				_save(channel);

			END.clear();
			channel.write(END);
		}

		lastModified = jsonFilePath.toFile().lastModified();
	}

	private void _save(FileChannel sink) throws IOException {
		if(data.size() > 1) {
			Template start = null, end = null;
			for (int i = 0; i < data.size() - 1; i++) {
				Template t = data.get(i);
				if(t.position == null) {
					if(start != null) 
						write(start, end, sink);
					writeJson(t, sink, true);

					start = null;
					end = null;
				} else {
					if(start == null) {
						start = t;
						end = t;
					} else {
						if(end.position.end == t.position.start)
							end = t;
						else {
							write(start, end, sink);
							start = t;
							end = t;
						}
					}
				}
			}

			if(start != null) 
				write(start, end, sink);
		}
		writeJson(data.get(data.size() - 1), sink, false);
	}
	private void write(Template start, Template end, FileChannel sink) throws IOException {
		long st = start.position.start;
		long en = end.position.end;
		sink.transferFrom(indexed_json(), st, en - st);
		
		System.out.printf("transfered start:%s, count:%s \n", st, en - st);

		// LOGGER.fine(() -> String.format("transfered start:%s, count:%s ", st, en - st));
	}
	private void writeJson(Template template, FileChannel sink, boolean appendComma) throws IOException {
		StringWriter2 sw = templateWriter();
		template.writeJson(sw);
		if(appendComma)
			sw.append(",\n");
		try {
			write(sw.getBuilder(), sink);
		} finally {
			sw.clear();
		}
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

		if(t.position != null && newData != null && newData.remove(t)) {
			data.add(t.order, t);
			modified = true;
			t.position = null;
			System.out.println("SET MODIFIED: \n"+t);
		} else
			System.out.println("MODIFIED: \n"+t);
	}

	public class Template implements Comparable<Template> {
		public Position position;
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
			return position == null;
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

			StringWriter2 w = templateWriter();
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
}
