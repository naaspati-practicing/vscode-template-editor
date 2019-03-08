package sam.pkg.jsonfile.infile;

import static java.util.Collections.emptyMap;
import static sam.myutils.Checker.assertTrue;
import static sam.myutils.Checker.isEmpty;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.myutils.Checker.isNotEmpty;
import static sam.myutils.Checker.isNotEmptyTrimmed;
import static sam.pkg.jsonfile.infile.Utils.putMeta;
import static sam.io.IOUtils.*;
import static sam.pkg.jsonfile.infile.Utils.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
import sam.io.infile.DataMeta;
import sam.io.infile.TextInFile;
import sam.myutils.Checker;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.api.JsonManager;
import sam.pkg.jsonfile.infile.JsonManagerImpl.JsonMeta;
import sam.pkg.jsonfile.infile.Utils.WriteMeta;
import sam.string.StringSplitIterator;

abstract class JsonMetaManager {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	private boolean newFound;
	private JsonMeta[] jsonMetas;

	public JsonMetaManager() throws IOException {
		loadAll();
	}

	public JsonMeta jsonMetas(JsonFileImpl json) {
		return jsonMetas[json.id];
	}

	protected abstract Path snippetDir();
	protected abstract JsonLoader loader();
	protected abstract DataMeta metaTypes(MetaType key);
	protected abstract void metaTypes(MetaType key, DataMeta value);
	protected abstract Path subpath(Path f);
	protected abstract JsonMeta jsonMeta(JsonFileImpl json, long lastmodified, DataMeta textMeta, DataMeta datametaMeta);
	protected JsonMeta jsonMeta(JsonFileImpl json) {
		return jsonMeta(json, 0, null, null);
	}

	private JsonMeta[] loadAll() throws IOException {
		Checker.assertIsNull(jsonMetas);

		Path[] p = JsonManager.walk(snippetDir()).toArray(Path[]::new);
		this.jsonMetas = new JsonMeta[p.length];

		for (int i = 0; i < p.length; i++) 
			jsonMetas[i] = jsonMeta(new JsonFileImpl(i, p[i], loader()));

		logger.debug("NEW JSON FOUND: "+jsonMetas.length);
		return jsonMetas;
	}

	public JsonMetaManager(TextInFile smallfile, StringResources r) throws IOException {
		DataMeta dm = metaTypes(MetaType.JSON_FILE_PATH);

		if(dm == null || dm.size == 0) {
			loadAll();
			return;
		}

		ByteBuffer buffer = r.buffer;
		StringBuilder sb = r.sb();

		smallfile.readText(dm, buffer, r.chars, r.decoder, sb);

		if(isNotEmptyTrimmed(sb)) {
			loadAll();
			return;
		}

		Map<Integer, Path> idPathMap = new HashMap<>();
		int n = 0;
		Iterator<String> array = new StringSplitIterator(sb, '\t');
		Path snippetDir = snippetDir();

		while (array.hasNext()) {
			String s = array.next();
			idPathMap.put(n++, isEmptyTrimmed(s) ? null : snippetDir.resolve(s));
		}

		if(!idPathMap.isEmpty()) 
			idPathMap.values().removeIf(Checker::notExists);

		if(idPathMap.isEmpty()) {
			loadAll();
			return;
		}

		List<Path> newJsons = null;
		Map<Path, JsonMeta> result = read(smallfile, r.buffer, idPathMap);

		if(isEmpty(result)) {
			loadAll();
			return;
		}

		Iterator<Path> json_files = JsonManager.walk(snippetDir).iterator();

		while (json_files.hasNext()) {
			Path f = json_files.next();
			JsonMeta meta = result.get(f);

			if(meta != null && meta.lastmodified() != f.toFile().lastModified()) {
				logger.warn("reset JsonFileImpl: lastmodified({} -> {}), path: \"{}\"", meta.lastmodified(), f.toFile().lastModified(), subpath(f));
				meta = null;
				result.remove(f);
			}

			if(meta == null) {
				if(newJsons == null)
					newJsons = new ArrayList<>();
				newJsons.add(f);
			}
		}

		this.newFound = isNotEmpty(newJsons);

		if(newFound) {
			BitSet setIds = new BitSet();
			result.forEach((p, m) -> setIds.set(m.jsonfile.id));
			int nextId = 0;

			for (Path p : newJsons) {
				int id = 0;
				while(setIds.get(id = nextId++)) {}

				String subpath = subpath(p).toString();

				result.put(p, jsonMeta(new JsonFileImpl(id, p, loader())));
				logger.info("new JsonFileImpl id: {}: %SNIPPET_DIR%\\{}", id, subpath);
			}
		}

		this.jsonMetas = new JsonMeta[result.isEmpty() ? 0 : result.values().stream().mapToInt(m -> m.jsonfile.id).max().getAsInt() + 1];
		result.forEach((s,m) -> this.jsonMetas[m.jsonfile.id] = m);
	}

	void close(TextInFile smallfile, StringResources r, long pos) throws IOException {
		IOUtils.ensureCleared(r.buffer);

		if(newFound) {
			StringBuilder sb = r.sb();
			sb.setLength(0);

			for (JsonMeta m : jsonMetas) 
				sb.append(subpath(m.jsonfile.source)).append('\t');

			DataMeta d = smallfile.write(sb, r.encoder, r.buffer);
			metaTypes(MetaType.JSON_FILE_PATH, d);

			sb.setLength(0);
			r.buffer.clear();
			r.chars.clear();
		}

		ByteBuffer buf = r.buffer;
		int size = jsonMetas.length * JsonMeta.BYTES;
		if(buf.capacity() < size)
			buf = ByteBuffer.allocate(size);
		
		buf.clear();
		
		for (JsonMeta m : jsonMetas)
			m.put(buf);

		buf.flip();
		DataMeta dm = smallfile.write(buf);
		assertTrue(dm.size == jsonMetas.length * JsonMeta.BYTES, () -> String.format("w.size (%s) == jsonMetas.length (%s) * JSON_META_BYTES (%s) = %s", dm.size, jsonMetas.length, JsonMeta.BYTES, jsonMetas.length * JsonMeta.BYTES));

		metaTypes(MetaType.JSON_META_DATAMETA, dm);
	}

	public Map<Path, JsonMeta> read(TextInFile smallfile, ByteBuffer buffer, Map<Integer, Path> idPathMap) throws IOException {
		DataMeta dm;

		if(isEmpty(idPathMap)) {
			return emptyMap();
		} else {
			dm = metaTypes(MetaType.JSON_META_DATAMETA);
			if(dm == null || dm.size == 0)
				return emptyMap();
		}

		IOUtils.ensureCleared(buffer);
		Map<Path, JsonMeta> result = new HashMap<>(); 

		smallfile.read(dm, buffer, b -> {
			while(b.remaining() >= JsonMeta.BYTES) {
				int id = b.getInt();
				Path source = idPathMap.remove(id);
				if(source == null) 
					logger.warn("no source found for id: {}", id);
				else {
					JsonMeta meta = jsonMeta(new JsonFileImpl(id, source, loader()), b.getLong(), readMeta(b), readMeta(b));
					result.put(source, meta);	
				}
			}
			compactOrClear(b);
		});
		return result;
	}
}

