package sam.pkg.jsonfile.toast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.json.JSONTokener;

import sam.pkg.jsonfile.api.JsonFile;
import sam.pkg.jsonfile.api.JsonManager;
import sam.pkg.jsonfile.api.Template;

public class JsonMan implements JsonManager {
	private final Path snippetDir;
	private final int count;
	private final List<JsonFile> files;
	
	public JsonMan(Path snippetDir) throws IOException {
		this.snippetDir = snippetDir;
		this.count = snippetDir.getNameCount();
		
		files = walk(snippetDir).map(JFile::new).collect(Collectors.toList());
	}

	@Override
	public void close() throws Exception {
	}

	@Override
	public List<JsonFile> getFiles() {
		return Collections.unmodifiableList(files);
	}
	
	class TMP implements Template {
		final JSONObject json;
		final String id;

		public TMP(String id, JSONObject json) {
			this.id = id;
			this.json = json;
		}

		@Override
		public String id() {
			return id;
		}
		public String get(String key) {
			return json.optString(key);
		}
		public void set(String key, String value) {
			json.put(key, value);
		}
		@Override
		public String prefix() {
			return get(PREFIX);
		}
		@Override
		public String description() {
			return get(DESCRIPTION);
		}
		@Override
		public String body() {
			return get(BODY);
		}
		@Override
		public void prefix(String s) {
			set(PREFIX, s);
		}
		@Override
		public void description(String s) {
			set(DESCRIPTION, s);
		}

		@Override
		public void body(String s) {
			set(BODY, s);
		}

		@Override
		public void save() {
			// TODO Auto-generated method stub
		}
	}
	
	class JFile implements JsonFile {
		final Path source;
		List<Template> list;
		Throwable e;

		public JFile(Path source) {
			this.source = source;
		}
		
		@Override
		public Path source() {
			return source;
		}
		
		@Override
		public String toString() {
			return source.subpath(count, source.getNameCount()).toString();
		}

		@Override
		public void getTemplates(BiConsumer<List<Template>, Throwable> consumer) {
			if(list != null || e != null) {
				consumer.accept(list, e);
				return;
			}
			
			try {
				JSONObject obj = new JSONObject(new JSONTokener(Files.newInputStream(source, StandardOpenOption.READ)));
				List<Template> tmp = new ArrayList<>();
				
				obj.keySet().forEach(s -> tmp.add(new TMP(s, obj.getJSONObject(s))));
				
				this.list = Collections.unmodifiableList(tmp);
				consumer.accept(list, null);
			} catch (Throwable e) {
				this.e = e;
				consumer.accept(null, e);
			}
		}
		
	}

}
