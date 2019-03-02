package sam.pkg.jsonfile;

import static sam.pkg.Utils.SNIPPET_DIR;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.io.IOUtils;
import sam.nopkg.StringResources;

class JsonManagerUtils {
	private static final Logger LOGGER = LoggerFactory.getLogger(JsonManagerUtils.class);
	
	public List<Path> newJsons(ArrayList<JsonFile> oldJsons, Map<Path, Integer> pathJsonIdmap, DataMetas dataMetas, JsonManager manager, ByteBuffer buffer) throws IOException {
		Iterator<Path> itr = Files.walk(SNIPPET_DIR)
				.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"))
				.iterator();

		List<Path> newJsons = null;
		JsonMeta[] metas = dataMetas.jsonMetas(buffer);

		while (itr.hasNext()) {
			Path f = itr.next();
			Integer id = pathJsonIdmap.get(f);
			JsonMeta meta = id == null ? null : metas[id];

			if(meta == null) {
				if(newJsons == null)
					newJsons = new ArrayList<>();
				newJsons.add(f);
			} else {
				oldJsons.add(new JsonFile(id, meta.lastmodified, meta.templateMeta, f, manager));
			} 
		}

		return newJsons;
	}

	public Map<Path, Integer> readJsons(FileChannel jsons, StringResources r) throws IOException {
		Map<Path, Integer> map = new HashMap<>();
		ByteBuffer buffer = r.buffer;
		StringBuilder sb = r.sb();

		int n = IOUtils.read(buffer, true, jsons);

		while(n >= 0) {
			if(buffer.remaining() < 8) {
				IOUtils.compactOrClear(buffer);
				n = IOUtils.read(buffer, false, jsons);
				if(n == -1)
					break;
			}

			int id = buffer.getInt();
			String subpath = readString(buffer, jsons, sb);

			map.put(SNIPPET_DIR.resolve(subpath), id);
		}

		return map;
	}

	public void handleNewJsons(Iterable<Path> newJsons, List<JsonFile> allJsons, FileChannel jsons, StringResources r, JsonManager manager) throws IOException {
		int namecount = SNIPPET_DIR.getNameCount();
		BitSet setIds = new BitSet();
		allJsons.forEach(j -> setIds.set(j.id));
		int nextId = 0;

		ByteBuffer buffer = r.buffer;
		buffer.clear();
		jsons.position(jsons.size());

		for (Path p : newJsons) {
			int id = 0;
			while(setIds.get(id = nextId++)) {}
			
			String subpath = p.subpath(namecount, p.getNameCount()).toString();

			allJsons.add(new JsonFile(id, 0, null, p, manager));
			LOGGER.info("new Json: %SNIPPET_DIR%\\".concat(subpath));

			if(buffer.remaining() < 8)
				IOUtils.write(buffer, jsons, true);

			buffer.putInt(id);
			writeString(subpath, buffer, jsons);
		}

		IOUtils.write(buffer, jsons, true);
	}

	private static void writeString(String subpath, ByteBuffer buffer, FileChannel file) throws IOException {
		buffer.putInt(subpath.length());

		for (int i = 0; i < subpath.length(); i++) {
			if(buffer.remaining() < 2)
				IOUtils.write(buffer, file, true);
			buffer.putChar(subpath.charAt(i));
		}
	}

	public static String readString(ByteBuffer buffer, ReadableByteChannel file, StringBuilder sink) throws IOException {
		if(buffer.remaining() < 4) {
			IOUtils.compactOrClear(buffer);
			int n = IOUtils.read(buffer, false, file);
			
			if(n < 4)
				throw new IOException();
		}
			
		int length = buffer.getInt();
		
		if(length == 0)
			return "";
		
		sink.setLength(0);

		while(sink.length() != length) {
			if(buffer.remaining() < 2) {
				IOUtils.compactOrClear(buffer);
				int n = IOUtils.read(buffer, false, file);
				if(n == -1)
					throw new IOException();
			}
			sink.append(buffer.getChar());
		}
		String s = sink.toString();
		sink.setLength(0);
		
		return s;
	}
}
