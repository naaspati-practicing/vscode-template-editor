package sam.pkg.jsonfile.infile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.myutils.Checker.isNotEmpty;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.console.ANSI;
import sam.io.IOUtils;
import sam.io.infile.DataMeta;
import sam.io.serilizers.StringIOUtils;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.api.JsonFile;
import sam.pkg.jsonfile.api.Template;
import sam.pkg.jsonfile.infile.JsonManagerImpl.JsonMeta;
import sam.string.StringWriter2;
import sam.string.SubSequence;
class JsonFileImpl implements JsonFile {
	private static final Logger LOGGER = LoggerFactory.getLogger(JsonFile.class);

	private List<TemplateImpl> templates;
	private List<Template> ummodifiable_templates;
	private Throwable error;
	
	private int json_mod;
	private int template_mode;
	
	public final Path source;
	public final int id;
	private boolean saved;
	
	private final JsonLoader manager;

	JsonFileImpl(int id, Path source, JsonLoader manager) {
		this.manager = manager;
		this.id = id;
		this.source = source;
	}
	
	@Override
	public Path source() {
		return source;
	}
	public boolean isSaved() {
		return saved;
	}

	private String name;
	@Override
	public String toString() {
		if(name != null) return name;
		return name = manager.subpath(source).toString();
	}

	public void getTemplates(BiConsumer<List<Template>, Throwable> consumer) {
		if(templates == null && error == null) {
			try {
				List<TemplateImpl> list = manager.loadTemplates(this);
				
				for (int i = 0; i < templates.size(); i++)  
					templates.get(i).order = i;
				
				set(list, null);
				LOGGER.info("loaded-templates: {}",  this);
			} catch (IOException e) {
				LOGGER.error("failed to load: {}", this, e);
				set(null, e);
			}	
		}
		consumer.accept(ummodifiable_templates, error);
	}

	private void set(List<TemplateImpl> templates, Throwable e) {
		error = e;
		this.templates = templates;
		this.ummodifiable_templates = templates == null ? null : Collections.unmodifiableList(templates);
	}

	private JSONObject getJson(TemplateImpl template) throws IOException {
		try(StringResources r = StringResources.get()) {
			StringBuilder sb = manager.loadText(template, r);

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

	private boolean remove(TemplateImpl t) {
		if(templates.remove(t)) {
			json_mod++;
			
			if(LOGGER.isDebugEnabled())
				LOGGER.debug("REMOVED: \n{}", t);
			else
				LOGGER.info("REMOVED: TemplateImpl[{}]", t.json_id);
				
			return true;
		}
		return false;
	}
	
	private void save(Collection<TemplateImpl> newData) {
		json_mod++;
		int n = json_mod;

		if(isNotEmpty(newData)) {
			for (TemplateImpl t : newData) {
				templates.add(t.order, t);
				json_mod++;
				
				if(LOGGER.isDebugEnabled())
					LOGGER.debug("SET MODIFIED: \n{}", t);
				else
					LOGGER.info("SET MODIFIED: TemplateImpl[{}]", t.json_id);
			}
		}
		if(n == json_mod)
			return;
		//FIXME
	}

	private static ByteBuffer START, END;
	private int save_mod = 0;

	public boolean save() throws IOException {
		if(save_mod == json_mod) {
			LOGGER.warn(ANSI.red("nothing to save: ")+source);
			return false;
		}

		try(FileChannel channel = FileChannel.open(source, WRITE, TRUNCATE_EXISTING);
				StringResources r = StringResources.get();
				) {

			if(START == null) {
				START = ByteBuffer.wrap("{\n".getBytes(r.CHARSET));
				END = ByteBuffer.wrap("\n}".getBytes(r.CHARSET));
			} 

			START.clear();
			channel.write(START);

			if(!templates.isEmpty()) {
				if(templates.size() > 1) {
					List<DataMeta> metas = new ArrayList<>();
					for (int i = 0; i < templates.size() - 1; i++) {
						TemplateImpl t = templates.get(i); 
						if(manager.meta(t) == null) 
							writeCache(t, null, r);

						metas.add(manager.meta(t));
					} 
					manager.transfer(metas, channel);	
				}

				StringBuilder sb = r.sb();
				sb.setLength(0);
				templates.get(templates.size() - 1).writeJson(new StringWriter2(sb));
				sb.append("\n}");

				StringIOUtils.write(b -> IOUtils.write(b, channel, false), sb, r.encoder, r.buffer, REPORT, REPORT);
				sb.setLength(0);
			} else {
				END.clear();
				channel.write(END);
			}
		}
		
		this.saved = true;
		return false;
	}

	void writeCache(TemplateImpl t, StringWriter2 sw, StringResources r) throws IOException {
		if(sw == null)
			sw = new StringWriter2(r.sb());

		StringBuilder sb = sw.getBuilder();

		if(sb.length() != 0)
			throw new IOException();

		t.writeJson(sw);
		sw.append(",\n");
		manager.write(t, sb, r);
		sw.clear();
	}

	private static final JSONObject  TEMPLATE_JSON = new JSONObject();
	private static final StringWriter2 templatesw = new StringWriter2();

	public class TemplateImpl implements Comparable<TemplateImpl>, Template {
		public final int id;
		private int order;
		private final String json_id;
		private String prefix, body, description;

		//create new
		private TemplateImpl(int id, String json_id) {
			this.json_id = json_id;
			this.prefix = json_id;
			this.body = json_id;
			this.id = id;
		}

		// partial load
		private TemplateImpl(int id, String json_id, String prefix) {
			checkEmpty(json_id, "id");

			this.id = id;
			this.json_id = json_id;
			this.prefix = prefix;
		}
		private void checkEmpty(String value, String variable) {
			if(isEmptyTrimmed(value))
				throw new IllegalArgumentException("empty value for variable: "+variable+",  value: "+value);
		}
		private TemplateImpl(int id, String json_id, JSONObject json) {
			checkEmpty(json_id, "id");

			loaded = true;
			this.json_id = json_id;
			this.id = id;
			this.prefix = json.getString(PREFIX);
			this.body = json.getString(BODY);
			this.description = (String)json.opt(DESCRIPTION);
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

			LOGGER.info("TemplateImpl loaded: {}", json_id);
		}

		private void writeJson(StringWriter2 w) {
			load();

			try {
				JSONObject json = TEMPLATE_JSON;

				json.put(PREFIX, prefix);
				json.put(BODY, body);
				json.put(DESCRIPTION, description);

				JSONObject.quote(json_id, w);
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
			return json_id;
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
		private String set(String old, String neew, String key) {
			load();

			if(key != DESCRIPTION)
				checkEmpty(neew, key);

			if(!Objects.equals(old, neew)) {
				template_mode++;
				return neew;
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
			return json_id.hashCode();
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			TemplateImpl other = (TemplateImpl) obj;
			return Objects.equals(json_id, other.json_id);
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
		public int compareTo(TemplateImpl o) {
			int n = Integer.compare(this.order, o.order);
			if(n == 0)
				return templates.contains(o) ? 1 : -1;
			return n;
		}
		public void remove() { 
			JsonFileImpl.this.remove(this);
		}

		public void save() {
			// TODO Auto-generated method stub
		}
		
	}

	TemplateImpl template(int id, String json_id, String prefix) {
		return new TemplateImpl(id, json_id, prefix);
	}

	public TemplateImpl template(int dmid, String key, JSONObject value) {
		return new TemplateImpl(dmid, key, value);
	}
}