package sam.pkg;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import sam.string.StringWriter2;
import sam.string.SubSequence;

/**
 * FIXME
 * implement save
 *   - transfer bytes of unchanged data from 1.data to target.json,
 *       except last Template. for last Template create data again (without ending with , (comma) )
 *       write last template.
 *   -  
 *     
 *   
 * @author Sameer
 *
 */
public class JsonFile implements Closeable {

	public final Path jsonFilePath;
	private long lastModified;
	public final int id;
	private boolean keysModified, file_modified; 
	private Throwable error;

	private List<Template> data;
	private List<Template> newData;
	private Map<Template, Position> positions = new IdentityHashMap<>();

	private List<Template> modifiedData;

	private FileChannel file;

	public static final String PREFIX = "prefix";
	public static final String BODY = "body";
	public static final String DESCRIPTION = "description";

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
	public StringWriter2 templateWriter() {
		if(_TEMPLATE_WRITER.getBuilder().length() != 0)
			throw new IllegalStateException(String.valueOf(_TEMPLATE_WRITER.getBuilder().length()));
		return _TEMPLATE_WRITER;
	}
	public ByteBuffer buffer() {
		if(_BUFFER.remaining() != _BUFFER.capacity())
			throw new IllegalStateException(String.valueOf(_BUFFER.remaining()));
		return _BUFFER;
	}

	public JsonFile(int id, Path path, boolean file_modified, long lastModified, boolean exists) {
		this.jsonFilePath = path;
		this.lastModified = lastModified;
		this.file_modified = file_modified;
		this.id = id;
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
	public void remove(Template t) {
		if(newData == null || !newData.remove(t)) {
			data.remove(t);
			keysModified = true;
			System.out.println("REMOVED: \n"+t);
		}
	}
	public Template add(String id, Template relativeTo) {
		if(newData == null)
			newData = new ArrayList<>();

		JSONObject e = new JSONObject();
		e.put(PREFIX, id);
		e.put(BODY, id);

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
					data.add(t);
					positions.put(t, pos);
				}
				System.out.println("loaded: "+Main.relativeToSelfDir(p));
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
	public JSONObject getJson(Template template) {
		init();

		Position p = positions.get(template);
		if(p == null) return null;

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
				file.read(buffer, p.start);
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

		Path p = dataPath(); 
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
				modifiedData = data;
				System.out.println("loaded json("+data.size()+"): "+this);
			} else {
				if(isEmpty(data))
					throw new IllegalStateException("data not loaded");
				file = FileChannel.open(p, READ, WRITE, CREATE);
			}
		} catch (JSONException | IOException e) {
			error = e;
			data = null;
			return;
		}
	}
	private Path dataPath() {
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
		if(hasError() || data == null) return;

		if(isNotEmpty(modifiedData)) {
			Path p = dataPath();

			try(FileChannel os = file = file != null ? file : FileChannel.open(p, CREATE, TRUNCATE_EXISTING, WRITE)) {
				os.position(os.size());
				StringWriter2 w = templateWriter();

				for (Template t : modifiedData) {
					long start = os.position();
					t.writeJson(w);
					w.append(",\n");
					write(w.getBuilder(), os);
					w.clear();
					positions.put(t, new Position(start, os.position()));
				}
			}
			System.out.println("saved: "+Main.relativeToSelfDir(p));
			lastModified = jsonFilePath.toFile().lastModified();
			keysModified = true;
		}
		if(keysModified || file_modified) {
			Path p = keysPath();
			try(OutputStream is = Files.newOutputStream(p, CREATE,TRUNCATE_EXISTING);
					DataOutputStream dos = new DataOutputStream(is);
					) {
				dos.writeInt(data.size());

				for (Template t : data) {
					Position pos = positions.get(t);
					dos.writeUTF(t.id);
					dos.writeUTF(t.prefix);
					dos.writeLong(pos.start);
					dos.writeLong(pos.end);
				}
			}
			System.out.println("saved: "+Main.relativeToSelfDir(p));
		}
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
	public Throwable error() {
		return error;
	}

	public class Template implements Comparable<Template> {
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
		public void saved() {
			// FIXME Auto-generated method stub
			throw new IllegalAccessError("NOT YET IMPLEMENTED");
		}
		public void remove() {
			// FIXME Auto-generated method stub
						throw new IllegalAccessError("NOT YET IMPLEMENTED");
		}
	}
}
