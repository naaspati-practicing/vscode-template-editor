package sam.pkg.jsonfile.api;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public interface JsonManager extends AutoCloseable {
	List<JsonFile> getFiles();
	static Stream<Path> walk(Path snippetDir) throws IOException {
		return Files.walk(snippetDir)
				.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"));
	}

	interface ParsedConsumer {
		void newInstance(int order, String key, JSONObject json);
	}

	default void parseJson(Path path, Charset charset, ParsedConsumer consumer) throws JSONException, IOException {
		
		//overrided to keep the order of keys;
		new JSONObject(new JSONTokener(Files.newBufferedReader(path, charset))) {
			private int n = 0;
			
			@Override
			public JSONObject put(String key, Object value) throws JSONException {
				super.put(key, value);
				if(has(key)) 
					consumer.newInstance(n++, key, (JSONObject)value);
				return this;
			}
		};
	}
}
