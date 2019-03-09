package sam.pkg.jsonfile.infile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.util.Arrays.sort;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static java.util.Comparator.comparingLong;
import static org.junit.jupiter.api.Assertions.assertNull;
import static sam.io.IOUtils.compactOrClear;
import static sam.io.IOUtils.ensureCleared;
import static sam.myutils.Checker.assertTrue;
import static sam.myutils.Checker.isEmpty;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.myutils.Checker.isNotEmpty;
import static sam.pkg.jsonfile.infile.Utils.readMeta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.io.BufferSupplier;
import sam.io.infile.DataMeta;
import sam.io.infile.TextInFile;
import sam.io.serilizers.StringIOUtils;
import sam.myutils.Checker;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.api.JsonManager;
import sam.pkg.jsonfile.infile.JsonManagerImpl.JsonMeta;
import sam.string.StringSplitIterator;

abstract class JsonMetaManager {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	private boolean newFound;
	private JsonMeta[] jsonMetas;
	private final Path path;

	public JsonMetaManager(Path path) throws IOException {
		this.path = path;
		loadAll();
	}

	public JsonMeta jsonMetas(JsonFileImpl json) {
		return jsonMetas[json.id];
	}

	protected abstract Path snippetDir();
	protected abstract JsonLoader loader();
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
	
	private class Temp {
		private long lastmodified;
		private DataMeta names_meta, data_meta; // location of templates string, in smallfile
		final int id;
		
		public Temp(int id, long lastmodified, DataMeta names_meta, DataMeta data_meta) {
			this.id = id;
			this.lastmodified = lastmodified;
			this.names_meta = names_meta;
			this.data_meta = data_meta;
		}
	}

	public JsonMetaManager(Path path, StringResources r) throws IOException {
		this.path = path;
		try(FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
			ByteBuffer buffer = r.buffer;
			buffer.clear();
			
			if(fc.read(buffer) < Integer.BYTES) {
				loadAll();
				return;
			}
			
			buffer.flip();
			int size = buffer.getInt();
			if(size < 1) {
				loadAll();
				return;
			}
			
			logger.debug("size: {}", size);
			Temp[] temp = new Temp[size]; 

			for (int i = 0; i < size; i++) 
				temp[i] = new Temp(i, buffer.getLong(), readMeta(buffer), readMeta(buffer));

			StringBuilder sb = r.sb();
			StringIOUtils.read(new BufferSupplier() {
				boolean first = true;
				boolean end = false;
				@Override
				public ByteBuffer next() throws IOException {
					if(!first) {
						compactOrClear(buffer);
						end = fc.read(buffer) == -1;
						buffer.flip();
					}
					first = false;
					return buffer;
				}
				
				@Override
				public boolean isEndOfInput() throws IOException {
					return end;
				}
			}, sb, r.decoder, r.chars, REPORT, REPORT);

			if(isEmptyTrimmed(sb)) {
				loadAll();
				return;
			}

			Path[] paths = new Path[temp.length];
			int n = 0;
			Iterator<String> array = new StringSplitIterator(sb, '\t');
			Path snippetDir = snippetDir();

			while (array.hasNext()) {
				String s = array.next();
				paths[n++] = isEmptyTrimmed(s) ? null : snippetDir.resolve(s);  
			}
			
			Checker.assertTrue(temp.length == paths.length);

			if(paths.length == 0 || Checker.allMatch(Checker::notExists, paths)) {
				loadAll();
				return;
			} 
			
			Map<Path, Temp> map = new HashMap<>();
			for (int i = 0; i < paths.length; i++)
				map.put(paths[i], temp[i]);
				
			Iterator<Path> json_files = JsonManager.walk(snippetDir).iterator();
			List<Path> newJsons = new ArrayList<>();

			while (json_files.hasNext()) {
				Path f = json_files.next();
				Temp meta = map.get(f);

				if(meta != null && meta.lastmodified != f.toFile().lastModified()) {
					logger.warn("reset JsonFileImpl: lastmodified({} -> {}), path: \"{}\"", meta.lastmodified, f.toFile().lastModified(), subpath(f));
					meta = null;
					temp[meta.id] = null;
				}

				if(meta == null) 
					newJsons.add(f);
			}

			this.newFound = isNotEmpty(newJsons);
			JsonMeta[] metas = new JsonMeta[temp.length + newJsons.size()];
			
			for (Temp t : temp) {
				if(t != null) 
					metas[t.id] = jsonMeta(new JsonFileImpl(t.id, paths[t.id], loader()), t.lastmodified, t.names_meta, t.data_meta);
			}

			if(newFound) {
				int id = 0;
				
				for (Path p : newJsons) {
					while(metas[id] != null) {id++;}
					assertNull(metas[id]);
					
					String subpath = subpath(p).toString();

					metas[id] = jsonMeta(new JsonFileImpl(id, p, loader())); 
					logger.info("new JsonFileImpl id: {}: %SNIPPET_DIR%\\{}", id, subpath);
				}
				
				int max = Arrays.stream(metas).filter(t -> t != null).mapToInt(t -> t.jsonfile.id).max().getAsInt();
				if(max + 1 != metas.length)
					metas = Arrays.copyOf(metas, max + 1);
			}
			this.jsonMetas = metas;
		}

	}

	void close(StringResources r) throws IOException {
		
		
		ensureCleared(r.buffer);

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
	}
	
	public JsonFileImpl[] toFiles() {
		JsonFileImpl[] files = stream(jsonMetas).filter(Objects::nonNull).map(m -> m.jsonfile).toArray(JsonFileImpl[]::new);
		sort(files, comparingLong(j -> jsonMetas[j.id].lastmodified() < 0 ? Long.MAX_VALUE : jsonMetas[j.id].lastmodified()));
		return files;
	}
}

