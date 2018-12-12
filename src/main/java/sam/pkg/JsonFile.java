package sam.pkg;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import sam.console.ANSI;
import sam.io.serilizers.StringWriter2;
import sam.string.StringUtils;

public class JsonFile implements Closeable {
	public final Path path;
	private long lastModified;
	private boolean modified;
	public final int id;
	private boolean exists, keysModified, jsonFileModified;
	private Throwable error;
	private JSONObject parsed;
	private Map<String, JSONObject> newEntries;

	public static final String PREFIX = "prefix";
	public static final String BODY = "body";
	public static final String DESCRIPTION = "description";

	public JsonFile(int id, Path path, long lastModified, boolean modified, boolean exists) {
		this.path = path;
		this.lastModified = lastModified;
		this.modified = modified;
		this.id = id;
		this.exists = exists;
	}
	public static String get(JSONObject obj, String key) {
		return obj.has(key) ? obj.getString(key) : null;
	}
	public long lastModified() {
		return lastModified;
	}
	public boolean modified() {
		return modified;
	}
	public boolean exists() {
		return exists;
	}
	public JsonFile(DataInputStream dis) throws IOException {
		this.id = dis.readInt();
		this.lastModified = dis.readLong();
		this.path = Paths.get(dis.readUTF());
		this.exists = Files.exists(path);

		if(exists) {
			long lm = path.toFile().lastModified();
			modified = lastModified != lm;
			lastModified = lm;
			if(modified)
				System.out.println(ANSI.yellow("modified: ")+Main.relativeToSnippedDir(path));
		} else {
			System.out.println(ANSI.red("deleted: ")+Main.relativeToSnippedDir(path));
		}
	}
	public void write(DataOutputStream dos) throws IOException {
		dos.writeInt(this.id);
		dos.writeLong(this.lastModified);
		dos.writeUTF(this.path.toString());
	}

	private String name;
	@Override
	public String toString() {
		if(name != null) return name;
		return name = path.subpath(Main.snippet_dir_count, path.getNameCount()).toString();
	}
	public void remove(Key key) {
		init();
		if(keys.remove(key))
			keysModified = true;
		
		if(parsed.remove(key.entryId) != null) {
			jsonFileModified = true;
			System.out.println("removed(JSON): "+new JSONObject(key));
		}
		if(newEntries != null &&  newEntries.remove(key.entryId) != null) 
			System.out.println("removed(TEMP): "+new JSONObject(key));
	}
	public Key add(String id) {
		if(newEntries == null)
			newEntries = new HashMap<>();

		JSONObject e = new JSONObject();
		newEntries.put(id, e);
		Key k = new Key(this, id, id);
		keys.add(k);
		System.out.println("temp-added: "+new JSONObject(k));
		keysModified = true;
		return k;
	}
	private List<Key> keys;

	public Stream<Key> getKeys() {
		if(keys != null)
			return keys.stream();

		if(modified()) 
			init();
		else {
			Path p = keysPath();
			if(Files.notExists(p))
				init();
			else {
				try {
					keys = Files.lines(p).map(s -> StringUtils.split(s, '\t')).map(s -> new Key(this, s[0], s[1])).collect(Collectors.toList());
				} catch (IOException e) {
					error = e;
					return Stream.empty();
				}
			}
		}

		return keys == null ? Stream.empty() : keys.stream();
	}
	private Path keysPath() {
		return Main.CACHE_DIR.resolve(id+".keys");
	}
	public boolean hasError() {
		return error != null;
	}
	private void init() {
		if(parsed != null || hasError()) return;

		try {
			parsed = new JSONObject(new JSONTokener(Files.newInputStream(path)));
			System.out.println("loaded json: "+Main.relativeToSnippedDir(path));
		} catch (JSONException | IOException e) {
			error = e;
			return;
		}

		keysModified = keysModified || modified();
		keys = new ArrayList<>((int)(parsed.length()*1.5));

		parsed.keySet()
		.forEach(s -> {
			keys.add(new Key(this, s, s));
			String s2 = get(parsed.getJSONObject(s), PREFIX);
			if(!s.equals(s2))
				keys.add(new Key(this, s2, s));
		});
	}
	@Override
	public void close() throws IOException {
		if(hasError()) return;

		if(keysModified) {
			StringBuilder sb = new StringBuilder();
			keys.forEach(k -> sb.append(k.key).append('\t').append(k.entryId).append('\n'));
			Path  p = keysPath();
			StringWriter2.setText(p, sb);
			System.out.println("saved: "+Main.relativeToSelfDir(p));

		}
		if(jsonFileModified) {
			try(BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.TRUNCATE_EXISTING)) {
				parsed.write(writer, 4, 0);
			}
			System.out.println("saved: "+Main.relativeToSnippedDir(path));
			lastModified = path.toFile().lastModified();
		}
	}
	public JSONObject jsonObject(Key key) {
		init();
		JSONObject o = newEntries == null ? null : newEntries.get(key.entryId);
		if(o != null)
			return o;
		return parsed.getJSONObject(key.entryId);
	}
	public Throwable error() {
		return error;
	}
	public void setSaved(Key key) {
		jsonFileModified = true;

		JSONObject o = newEntries == null ? null : newEntries.remove(key.entryId);
		if(o != null)
			parsed.put(key.entryId, o);
	}
}
